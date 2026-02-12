;; squint does not have test ns, steal from https://github.com/nextjournal/clojure-mode/blob/main/src-squint/nextjournal/clojure_mode_tests/macros.cljc
(ns cljdoc.client.test-macros)

(defmacro deftest [var-name & body]
  `(do
     (~'js* "// ~{}\n" ~var-name)
     (println "--[" ~(str var-name) "]--")
     ~@body))

(defn- is*
  ([expr] (is* expr nil))
  ([expr msg]
   (if (and (seq? expr) (= '= (first expr)))
     (let [[_ actual expected] expr]
       `(assert.deepStrictEqual ~expected ~actual ~msg))
     ;; assert errors for these will be awful, but this is a hack.
     `(assert ~expr ~msg))))

(defmacro is
  [& args]
  (apply is* args))
