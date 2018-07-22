(ns badigeon.prompt)

(defn prompt [prompt]
  (print prompt)
  (flush)
  (read-line))

(defn prompt-password [prompt]
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
