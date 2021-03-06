;; Filename: src/freeswitch_clj/core.clj
;; Author: Titon Barua <titanix88@gmail.com>
;; Copyright: 2017 Messrs Concitus, Dhaka, BD. <contact@concitus.com>
;;
;; This work is distributed under MIT Public License.
;; Please see the attached LICENSE file in project root.
(ns ^{:doc "Contains functions to communicate with freeswitch using ESL."
      :author "Titon Barua"}
 freeswitch-clj.core
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.set :as set]

            [clj-uuid :as uuid]
            [aleph.tcp :as tcp]
            [manifold.stream :as stream]
            [taoensso.timbre :as log]

            [freeswitch-clj.protocol :refer [decode-all
                                             encode
                                             parse-command-reply
                                             parse-api-response
                                             parse-bgapi-response
                                             parse-event]]))

(def
  ^{:private true
    :doc "This events are auto-handled by some high-level functions."}
  special-events
  #{"LOG"
    "BACKGROUND_JOB"
    "CHANNEL_EXECUTE"
    "CHANNEL_EXECUTE_COMPLETE"
    "CHANNEL_HANGUP"
    "CHANNEL_HANGUP_COMPLETE"})

(defn- log-with-conn
  "Log something with the context of conenction."
  [{:keys [mode aleph-stream] :as conn} lvl msg & args]
  (let [sdesc (.description aleph-stream)]
    (log/logf lvl
              "[%s L%s <-> R%s] %s"
              (name mode)
              (str (get-in sdesc [:sink :connection :local-address]))
              (str (get-in sdesc [:sink :connection :remote-address]))
              (str/join " " (cons msg args)))))

(defn- log-wc-debug
  "Log a debug level message with connection context."
  [conn msg & args]
  (apply log-with-conn conn :debug msg args))

(defn- log-wc-info
  "Log an info level message with connection context."
  [conn msg & args]
  (apply log-with-conn conn :info msg args))

(defn- send-str
  "Send some string data to freeswitch."
  [{:keys [closed? aleph-stream] :as conn} data]
  (if-not (realized? closed?)
    (stream/put! aleph-stream data)
    (throw (Exception. "Can't send data to through closed connection."))))

(defn- norm-token
  "Normalize a token, by trimming and upper-casing it."
  [tok]
  (str/upper-case (str/trim (str tok))))

(defn norm-kv
  "Convert a key-val pair into a normalized string, joined by colon."
  [[k v]]
  (str (norm-token (name k))
       ":"
       (norm-token (str v))))

(defn- detect-special-events
  "Inspect outgoing event un/subscription commands to keep tabs on special events."
  [{enabled :enabled-special-events :as conn} cmd & cmd-args]
  (let [[cmd & cmd-args] (as-> (cons cmd cmd-args) $
                               (apply str $)
                               (str/trim $)
                               (str/upper-case $)
                               (str/split $ #"\s+"))
        cmd-args' (set cmd-args)]
    (let [found (set/intersection special-events cmd-args')]
      ;; One fun fact about freeswitch protocol:
      ;; You can prefix the first token with arbitrary junk.
      ;; For example - both 'event' and 'eventsarefunny' are
      ;; acceptable. To maintain compatibility, we are doing
      ;; a starts-with? based match.
      (cond
        (str/starts-with? cmd "EVENT")
        (swap! enabled merge (zipmap found (repeat true)))

        (str/starts-with? cmd "NIXEVENT")
        (swap! enabled merge (zipmap found (repeat false)))

        (str/starts-with? cmd "NOEVENTS")
        (swap! enabled merge (zipmap special-events (repeat false)))

        (str/starts-with? cmd "MYEVENTS")
        (swap! enabled merge (zipmap special-events (repeat true)))

        :default nil))))

(defn req
  "Make a request to freeswitch.

  Args:
  * conn - Freeswitch connection.

  Returns:
  An `async/promise-chan` which returns the response when available.

  NOTE:
  This is a low level function, intended to be used by other
  high-level functions like `req-cmd` etc."
  [conn
   cmd-line
   cmd-hdrs
   cmd-body]
  (let [{:keys [rslt-chans req-index]} conn]
    (apply detect-special-events conn cmd-line)
    (log-wc-debug conn
                  (format "Sending request; cmd-line: %s, cmd-hdrs: %s, cmd-body: %s"
                          (pr-str cmd-line)
                          (pr-str cmd-hdrs)
                          (pr-str cmd-body)))
    (dosync
     (let [rchan (async/promise-chan)]
       ;; If we don't record the order of our requests, there is no way to
       ;; know which resp is for which request. We must infer the association
       ;; from the order at which responses are received, as freeswitch serves
       ;; requests on a fifo basis.
       (swap! rslt-chans assoc @req-index rchan)
       (send-str conn (encode cmd-line cmd-hdrs cmd-body))
       (alter req-index inc)
       rchan))))

(defn- init-inbound
  "Do some initiation rites in inbound mode."
  [conn])

(defn- init-outbound
  "Do some initiation rites in outbound mode."
  [conn]
  (log-wc-debug conn "Initiation rites starting ...")
  (req conn ["linger"] {} nil)
  (req conn ["myevents"] {} nil)
  (log-wc-debug conn "Initiation rites complete."))

(defn bind-event
  "Bind a handler function to the event.

  Args:
  * conn - The connection map.
  * handler - The event handler function. It's signature should be:
              `(fn [conn event-map])`. Handler return value does not
              matter.

  Keyword args:
  All key value pairs are treated as event headers to match against.

  Returns:
  nil

  Example:
      ;; Set a catch-all-stray event handler.
      (bind-event conn
                  (fn [conn event]
                    (println \"I match all stray events!\")))

      ;; Create a BACKGROUND_JOB event handler.
      (bind-event conn
                  (fn [conn event]
                    (println \"I match all BG_JOB events.\"))
                  :event-name \"BACKGROUND_JOB\")

      ;; Create BACKGROUND_JOB event handler for a specific job-uuid.
      (bind-event conn
                  (fn [conn event]
                    (println \"I match BG_JOB with specific UUID.\"))
                  :event-name \"BACKGROUND_JOB\"
                  :job-uuid \"1234\")

  Note:
  * This does not send an 'event' command to freeswitch.
  * Generally, you should use it's higher-level cousin: `req-event`.
  * Only one event handler is allowed per match criteria. New bindings
    override the old ones.
  * Specific handlers has higher priority than generalized ones.
    The catch-all-stray handler has lowest priority.
  "
  [conn
   handler
   & {:as event-headers}]
  {:pre [(fn? handler)]}
  (let [hkey (set (map norm-kv event-headers))]
    (swap! (:event-handlers conn) assoc hkey handler)))

(defn unbind-event
  "Unbind the associated handler for an event.

  Args:
  * conn - The connection map.

  Keyword args:
  Event headers to match against.

  Returns:
  nil"
  [conn
   & {:as event-headers}]
  (let [hkey (set (map norm-kv event-headers))]
    (when (empty? hkey)
      (log-with-conn conn :warn "Binding a catch-all-stray handler!"))
    (swap! (:event-handlers conn) dissoc hkey)))

(declare disconnect)
(defn- send-password
  [{:keys [password authenticated?] :as conn} msg]
  (async/go (let [{:keys [ok]} (async/<! (req conn ["auth" password] {} nil))]
              (if-not ok
                (do (log-with-conn conn :error "Failed to authenticate.")
                    (disconnect conn)
                    (deliver authenticated? false))
                (do (log-wc-debug conn "Authenticated."
                                  (deliver authenticated? true)))))))

(defn- fulfil-result
  [{:keys [rslt-chans resp-index] :as conn} result]
  (dosync
   (async/put! (@rslt-chans @resp-index) result)
   (swap! rslt-chans dissoc @resp-index)
   (alter resp-index inc)))

(defn- enqueue-event
  [{:keys [event-chan] :as conn} event]
  (async/put! event-chan event))

;; How this works:
;; Event handlers are put into a map, associated with a set made
;; from all the headers they are interested in. For example,
;; Here's a sample value of the event-handlers map: {
;;   #{} <catch-all-stray-events-handler>
;;   #{"EVENT-NAME:BACKGROUND_JOB"} <general-bgjob-handler-func>
;;   #{"EVENT-NAME:BACKGROUND_JOB" "JOB-UUID:1234"} <specific-bgjob-handler>
;; }
;;
;; During matching, we transform the event into a similar set. Then
;; select those keys of event-handlers map, which are subset of the
;; event set. If multiple subset is found, we select the biggest subset
;; for more specific match.
;;
;; Note:
;; If multiple biggest subset are found, there is no guarantee
;; about which one will get selected.
(defn- handle-event
  [{:keys [event-handlers] :as conn} event]
  (let [event-keys (set (map norm-kv event))
        hkey (->> (keys @event-handlers)
                  (filter #(set/subset? % event-keys))
                  (reduce #(max-key count %1 %2) nil))
        handler (get @event-handlers hkey)]
    (if handler
      (do (handler conn event)
          true)
      (do (log-with-conn conn
                         :warn
                         "Ignoring handler-less event:"
                         event
                         (event :event-name))
          false))))

(defn- spawn-event-handler
  "Create a go-block to handle incoming events."
  [{:keys [event-chan] :as conn}]
  (async/go
    (loop [event (async/<! event-chan)]
      (when event
        (handle-event conn event)
        (recur (async/<! event-chan))))))

(defn close
  "Close a freeswitch connection.

  Note:
  Normally, you should use `disconnect` function to
  gracefully disconnect, which sends protocol epilogue."
  [{:keys [aleph-stream event-chan closed?] :as conn}]
  (if-not (realized? closed?)
    (do (.close aleph-stream)
        (async/close! event-chan)
        (deliver closed? true))))

(defn- handle-disconnect-notice
  [{:keys [connected? aleph-stream] :as conn} msg]
  (log-wc-debug conn "Received disconnect-notice.")
  (close conn))

(defn- create-aleph-data-consumer
  "Create a data consumer to process incoming data in an aleph stream."
  [{:keys [rx-buff aleph-stream] :as conn}]
  (fn [data-bytes]
    (if (nil? data-bytes)
      ;; Handle disconnection.
      (do (log-wc-debug conn "Disconnected.")
          (close conn))

      ;; Handle incoming data.
      (let [data (String. data-bytes)
            [msgs data-rest] (decode-all (str @rx-buff data))]

        ;; Do different things based on message received.
        (doseq [m msgs]
          (let [ctype (get-in m [:envelope-headers :content-type])]
            (log-wc-debug conn "Received msg:" (pr-str m))
            (case ctype
              "auth/request" (send-password conn m)
              "command/reply" (fulfil-result conn (parse-command-reply m))
              "api/response" (fulfil-result conn (parse-api-response m))
              "text/event-plain" (enqueue-event conn (parse-event m))
              "text/event-json" (enqueue-event conn (parse-event m))
              "text/event-xml" (enqueue-event conn (parse-event m))
              "text/disconnect-notice" (handle-disconnect-notice conn m)
              (println "Ignoring unexpected content-type: " ctype))))
        (reset! rx-buff data-rest)))))

(defn- create-aleph-conn-handler
  "Create an incoming connection handler to use with aleph/start-server."
  [handler]
  (fn [strm info]
    (let [conn {:aleph-conn-info info
                :mode :fs-outbound

                :closed? (promise)
                :aleph-stream strm
                :rx-buff (atom "")
                :req-index (ref 0)
                :resp-index (ref 0)
                :rslt-chans (atom {})

                :event-handlers (atom {})
                :event-chan (async/chan)
                :enabled-special-events (atom (zipmap special-events (repeat false)))}]
      (log-wc-debug conn "Connected.")
      (spawn-event-handler conn)

      ;; Bind a consumer for incoming data bytes.
      (stream/consume (create-aleph-data-consumer conn) strm)

      ;; Run handler in a seperate async/tread.
      (async/thread
        (let [chan-data (-> (async/<!! (req conn ["connect"] {} nil))
                            (dissoc :ok :body :content-type))]
          (init-outbound conn)
          (handler conn chan-data)
          (close conn)))

      ;; Return the aleph stream.
      strm)))

(defn connect
  "Make an inbound connection to freeswitch.

  Keyword args:
  * :host - (optional) Hostname or ipaddr of the freeswitch ESL server.
            Defaults to 127.0.0.1.
  * :port - (optional) Port where freeswitch is listening.
            Defaults to 8021.
  * :password - (optional) Password for freeswitch inbound connection.
                Defaults to ClueCon.

  Returns:
  A map describing the connection.

  Note:
  Blocks until authentication step is complete."
  [& {:keys [host port password]
      :or {host "127.0.0.1"
           port 8021
           password "ClueCon"}
      :as kwargs}]
  (let [strm @(tcp/client {:host host :port port})]
    (let [conn {:host host
                :port port
                :password password
                :authenticated? (promise)
                :mode :fs-inbound

                :closed? (promise)
                :aleph-stream strm
                :rx-buff (atom "")
                :req-index (ref 0)
                :resp-index (ref 0)
                :rslt-chans (atom {})

                :event-handlers (atom {})
                :event-chan (async/chan)
                :enabled-special-events (atom (zipmap special-events (repeat false)))}]
      (log-wc-debug conn "Connected.")
      (spawn-event-handler conn)

      ;; Hook-up incoming data handler.
      (stream/consume (create-aleph-data-consumer conn) strm)

      ;; Block until authentication step is complete.
      (if @(conn :authenticated?)
        (do (init-inbound conn)
            conn)
        (do (close conn)
            (throw (ex-info "Failed to authenticate."
                            {:host (conn :host)
                             :port (conn :port)})))))))

(defn listen
  "Listen for outbound connections from freeswitch.

  Keyword args:
  * :port - Port to listen for freeswitch connections.
  * :handler - A function with signature: `(fn [conn chan-data])`.
               `conn` is a connection map which can be used with any
               requester function, like: `req-cmd`, `req-api` etc.
               `chan-data` is information about current channel.

  Returns:
  An aleph server object.

  Notes:
  * Connection auto listens for 'myevents'. But no event handler is bound.
  * To stop listening for connections, call `.close` method of the returned
    server object.
  "
  [& {:keys [port handler]
      :as kwargs}]
  {:pre [(integer? port)
         (fn? handler)]}
  (log/info "Listening for freeswitch at port: " port)
  (tcp/start-server (create-aleph-conn-handler handler)
                    {:port port}))

(defn disconnect
  "Gracefully disconnect from freeswitch by sending an 'exit' command.

  Args:
  * conn - The connection map.

  Returns:
  nil"
  [conn]
  (let [{:keys [closed?]} conn]
    (if-not (realized? closed?)
      (do (log-wc-debug conn "Sending exit request ...")
          (req conn ["exit"] {} nil))
      (log-with-conn conn :warn "Disconnected already."))))

(defn req-cmd
  "Send a simple command request.

  Args:
  * conn - The connection map.
  * cmd - The command string including additional arguments.

  Returns:
  A response map with key `:ok` bound to a boolean value
  describing success of the operation.

  Example:
      ;; Send a 'noevents' command.
      (req-cmd conn \"noevents\")

  Note:
  Don't use this function to send special commands, like -
  'bgapi', 'sendmsg' etc. Rather use the high level functions
  provided for each."
  [conn
   cmd]
  (let [m (re-find #"(?i)^\s*(bgapi|sendmsg|sendevent)" cmd)]
    (if m
      (throw
       (IllegalArgumentException.
        (format "Please use req-%s function instead." (m 1))))
      (async/<!! (req conn [cmd] {} nil)))))

(defn req-api
  "Convenience function to make an api request.

  Args:
  * conn - The connection map.
  * api-cmd - Api command string with arguments.

  Returns:
  A response map with following keys:
      * :ok - Whether the operation succeeded.
      * :result - The result of the api request.

  Example:
      ;; Send a 'status' api request.
      (println (req-api conn \"status\"))
  "
  [conn
   api-cmd]
  (let [cmd-line ["api" api-cmd]]
    (async/<!! (req conn cmd-line {} nil))))

(defn req-bgapi
  "Make a background api request.

  Args:
  * conn - The connection map.
  * handler - Result handler function. Signature is: `(fn [conn rslt])`.
              `rslt` is a map with following keys:
                * :ok - Designates success of api operation.
                * :result - Result of the api command.
                * :event - The event which delivered the result.
  * api-cmd : Api command string with arguments.

  Returns:
  The command response (not the api result).

  Example:
      ;; Execute a 'status' api request in background.
      (req-bgapi
        conn
        (fn [conn rslt] (println rslt))
        \"status\")
  "
  [conn
   handler
   api-cmd]
  (let [{:keys [enabled-special-events]} conn]
    ;; Ask freeswitch to send us BACKGROUND_JOB events.
    (if-not (@enabled-special-events "BACKGROUND_JOB")
      (req conn ["event" "BACKGROUND_JOB"] {} nil))
    (let [gen-job-uuid (str (uuid/v1))
          cmd-line ["bgapi" api-cmd]
          cmd-hdrs {:job-uuid gen-job-uuid}
          handler' (fn [con event]
                     (handler conn (parse-bgapi-response event)))]
      ;; By providing our own generated uuid, we can bind an
      ;; event handler before the response is generated. Relieing on
      ;; freeswitch generated uuid results in event handler function
      ;; being ran twich for jobs which complete too fast.
      (bind-event conn
                  handler'
                  :event-name "BACKGROUND_JOB"
                  :job-uuid gen-job-uuid)
      (let [{:keys [job-uuid] :as rslt} (async/<!! (req conn cmd-line cmd-hdrs nil))]
        (if job-uuid
          ;; Just a sanity check.
          (assert (= (norm-token gen-job-uuid)
                     (norm-token job-uuid)))
          ;; Remove the binding for a failed command.
          (unbind-event conn
                        :event-name "BACKGROUND_JOB"
                        :job-uuid gen-job-uuid))
        rslt))))

(defn req-event
  "Request to listen for an event and bind a handler for it.

  Args:
  * conn - The connection map.
  * handler - Event handler function with signature:
              `(fn [conn event-map])`.

  Keyword args:
  * :event-name - Name of the event. Special value `ALL` means
                  subscribe to all events and the handler matches
                  any value for :event-name.
  * All other keyword arguments are treated as event headers
    to match against. Like `:event-subclass` to match for custom
    events.

  Returns:
  Response of the event command.

  Examples:
     ;; Listen for a regular event.
     (req-event
       conn
       (fn [conn event]
         (println \"Got a call update!\"))
       :event-name \"CALL_UPDATE\")

     ;; Listen for a custom event with specific subclass.
     (req-event
       conn
       (fn [conn event]
         (println \"Inside a menu!\"))
       :event-name \"CUSTOM\"
       :event-subclass \"menu:enter\")

     ;; Listen for all events and setup a catch-all-stray handler.
     (req-event
       conn
       (fn [conn event]
         (println event))
       :event-name \"ALL\")
  "
  [conn
   handler
   & {:keys [event-name]
      :as event-headers}]
  {:pre [(fn? handler)
         (not (nil? event-name))]}
  (let [cmd-line ["event" event-name]
        event-headers (if (= (str/lower-case (str/trim event-name)) "ALL")
                        (dissoc event-headers :event-name)
                        event-headers)]
    ;; Bind a handler.
    (apply bind-event
           conn
           handler
           (flatten (seq event-headers)))

    ;; Request to listen for the event.
    (let [{:keys [ok] :as rslt} (async/<!! (req conn cmd-line {} nil))]
      ;; Unbind event handler if 'event' command failed.
      (when-not ok
        (apply unbind-event
               conn
               (flatten (seq event-headers))))
      rslt)))

(defn req-sendevent
  "Send a generated event to freeswitch.

  Args:
  * conn - The connection map.
  * event-name - The name of the event.

  Keyword args:
  * :body - (optional) The body of the event.
  * Any other keyword arguments are treated as headers for the event.

  Returns:
  Response of the command.
  "
  [conn
   event-name
   & {:keys [body] :as event-headers}]
  (let [cmd-line ["sendevent" event-name]
        cmd-hdrs (dissoc event-headers :body)
        cmd-body body]
    (async/<!! (req cmd-line cmd-hdrs cmd-body))))

(defn req-sendmsg
  "Make a 'sendmsg' request to control a call.

  Args:
  * conn - The connection map.

  Keyword args:
  * :chan-uuid - The UUID of target channel. Not required in outbound mode.
  * :body - (optional) Body of the message.
  * Any other keyword arguments are treated as headers for the message.

  Returns:
  Reponse of the command.

  Note:
  To execute a dialplan app or hangup the call, use higher
  level funcs like `req-call-execute` which provide automated
  event listener setup.
  "
  [conn
   & {:keys [chan-uuid body]
      :as headers}]
  (let [cmd-line (if chan-uuid
                   ["sendmsg" chan-uuid]
                   ["sendmsg"])
        cmd-hdrs (as-> headers $
                       (dissoc $ :body :chan-uuid)
                       (remove (fn [[k v]] (nil? v)) $))
        cmd-body body]
    (async/<!! (req conn cmd-line cmd-hdrs cmd-body))))

(defn req-call-execute
  "Send a 'sendmsg' request to a channel (or current channel, in case
  of freeswitch-outbound mode) to execute a dialplan application.

  Args:
  * app-cmd - The dialplan app to execute, including it's arguments.
              i.e. \"playback /tmp/myfile.wav\"

  Keyword args:
  * :chan-uuid - The UUID of the target channel. Unnecessary in outbound mode.
  * :start-handler - (optional) Function to process the CHANNEL_EXECUTE event.
  * :end-handler - (optional) Function to process the CHANNEL_EXECUTE_COMPLETE event.
  * :event-lock - (optional) Whether to execute apps in sync. Defaults to false.
  * :loops - (optional) The number of times the app will be executed. Defaults to 1.

  Returns:
  Command response.
  "
  [conn
   app-cmd
   & {:keys [chan-uuid
             start-handler
             end-handler
             event-lock
             loops]
      :or {event-lock false
           loops 1}
      :as kwargs}]

  (let [event-uuid (str (uuid/v1))
        {:keys [enabled-special-events]} conn
        [app-name app-arg] (str/split app-cmd #"\s+" 2)]
    ;; Setup :start-handler, if present.
    (when start-handler
      (when-not (@enabled-special-events "CHANNEL_EXECUTE")
        (assert (:ok (req-cmd conn "event CHANNEL_EXECUTE"))))
      (if chan-uuid
        (bind-event conn
                    start-handler
                    :event-name "CHANNEL_EXECUTE"
                    :unique-id chan-uuid
                    :application-uuid event-uuid)
        (bind-event conn
                    start-handler
                    :event-name "CHANNEL_EXECUTE"
                    :application-uuid event-uuid)))

    ;; Setup :end-handler, if present.
    (when end-handler
      (when-not (@enabled-special-events "CHANNEL_EXECUTE_COMPLETE")
        (assert (:ok (req-cmd conn "event CHANNEL_EXECUTE_COMPLETE"))))
      (if chan-uuid
        (bind-event conn
                    end-handler
                    :event-name "CHANNEL_EXECUTE_COMPLETE"
                    :unique-id chan-uuid
                    :application-uuid event-uuid)
        (bind-event conn
                    end-handler
                    :event-name "CHANNEL_EXECUTE_COMPLETE"
                    :application-uuid event-uuid)))

    ;; Make the 'sendmsg' request.
    (req-sendmsg conn
                 :chan-uuid chan-uuid
                 :call-command "execute"
                 :execute-app-name app-name
                 :event-uuid event-uuid
                 :loops loops
                 :event-lock event-lock
                 :content-type "text/plain"
                 :body app-arg)))

;; TODO: req-call-hangup
;; TODO: req-call-nomedia
