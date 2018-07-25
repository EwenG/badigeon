(ns badigeon.prompt)

(defn prompt
  "Read a string from *in*, prompting with string \"prompt\"."
  [prompt]
  (print prompt)
  (flush)
  (read-line))

(defn prompt-password
  "Read a string for the process standard input without echoing, prompting with string \"prompt\". Note that the process standard input may be different than *in* when using a socket REPL for example."
  [prompt]
  (if-let [console (System/console)]
    (do
      (print prompt)
      (flush)
      (.readPassword (System/console) "%s"  (into-array [prompt])))
    (throw (ex-info "No console device found." {}))))

(comment
  (prompt "Username: ")
  (prompt-password "Password: ")
  )
