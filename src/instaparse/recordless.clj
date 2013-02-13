(ns instaparse.recordless
  (:use clojure.pprint))

;TODO
;Listener list doesn't need to be set
; or maybe it should be set, but listeners shouldn't be functions
;Lazy-seq
;Regexps
;Reduce
;Kleene star and plus
;First and followed sets
;Error messages


(def stats (atom {}))
(defn add! [call] (swap! stats update-in [call] (fnil inc 0)))
(defn clear! [] (reset! stats {}))

(defn get-parser [grammar p]
  (get grammar p p))

(declare alt-parse cat-parse string-parse epsilon-parse end-parse non-terminal-parse)
(defn -parse [parser index tramp]
  (println "-parse" index parser)
  (if (keyword? parser) (non-terminal-parse parser index tramp)
    (case (:tag parser)
      :alt (alt-parse parser index tramp)
      :cat (cat-parse parser index tramp)
      :string (string-parse parser index tramp)
      :epsilon (epsilon-parse index tramp)
      :end (end-parse index tramp))))

(declare alt-full-parse cat-full-parse string-full-parse epsilon-full-parse 
         end-full-parse non-terminal-full-parse)
(defn -full-parse [parser index tramp]
  (println "-full-parse" index parser)
  (if (keyword? parser) (non-terminal-full-parse parser index tramp)
    (case (:tag parser)
      :alt (alt-full-parse parser index tramp)
      :cat (cat-full-parse parser index tramp)
      :string (string-full-parse parser index tramp)
      :epsilon (epsilon-full-parse index tramp)
      :end (end-full-parse index tramp))))

; The trampoline structure contains the grammar, text to parse, a stack and a nodes
; Also contains an atom to hold successes and one to hold index of failure point.
; grammar is a map from non-terminals to parsers
; text is a string
; stack is an atom of a vector containing items implementing the Execute protocol.
; nodes is an atom containing a map from [index parser] pairs to Nodes
; success contains a successful parse
; failure contains the index of the furthest-along failure

(defrecord Tramp [grammar text stack nodes success failure])
(defn make-tramp [grammar text] (Tramp. grammar text (atom []) (atom {}) (atom nil) (atom 0)))
  
; A Success record contains the result and the index to continue from
(defn make-success [result index] {:result result :index index})
(defn total-success? [tramp s]
  (= (count (:text tramp)) (:index s)))

; The trampoline's nodes field is map from [index parser] pairs to Nodes
; Nodes track the results of a given parser at a given index, and the listeners
; who care about the result.
; Both results and listeners are expected to be refs of sets.

(defn make-node [] {:listeners (atom #{}) :full-listeners (atom #{}) 
                    :results (atom #{}) :full-results (atom #{})})

;; Trampoline helper functions

;(defn push-stack-cached
;  "Pushes an item onto the trampoline's stack"
;  [tramp item]
;  (when (not (@(:prev-pushed tramp) item))
;    (swap! (:stack tramp) conj item)
;    (swap! (:prev-pushed tramp) conj item)))

(defn push-stack
  "Pushes an item onto the trampoline's stack"
  [tramp item]
  (add! :push-stack)
  (swap! (:stack tramp) conj item))

(defn listener-exists?
  "Tests whether node already has a listener"
  [tramp node-key]
  (let [nodes (:nodes tramp)]
    (when-let [node (@nodes node-key)]
      (seq @(:listeners node)))))

(defn full-listener-exists?
  "Tests whether node already has a full-listener"
  [tramp node-key]
  (let [nodes (:nodes tramp)]
    (when-let [node (@nodes node-key)]
      (or (seq @(:full-listeners node))
          (seq @(:listeners node))))))

(defn node-get
  "Gets node if already exists, otherwise creates one"
  [tramp node-key]
  (let [nodes (:nodes tramp)]
    (if-let [node (@nodes node-key)]
      node 
      (let [node (make-node)]
        (add! :create-node)
        (swap! nodes assoc node-key node)
        node))))

(defn push-result
  "Pushes a result into the trampoline's node.
   Categorizes as either result or full-result.
   Schedules notification to all existing listeners of result
   (Full listeners only get notified about full results)"
  [tramp node-key result]
  (let [node (node-get tramp node-key)
        total? (total-success? tramp result)
        results (if total? (:full-results node) (:results node))]
    (when (not (@results result))  ; when result is not already in @results
      (add! :push-result)
      (swap! results conj result)
      (doseq [listener @(:listeners node)]
        (push-stack tramp #(listener result)))
      (when total?
        (doseq [listener @(:full-listeners node)]
          (push-stack tramp #(listener result))))))) 

(defn push-listener
  "Pushes a listener into the trampoline's node.
   Schedules notification to listener of all existing results."
  [tramp node-key listener]
  (let [listener-already-exists? (listener-exists? tramp node-key)
        node (node-get tramp node-key)
        listeners (:listeners node)]
    (when (not (@listeners listener))  ; when listener is not already in listeners
      (add! :push-listener)
      (swap! listeners conj listener)
      (doseq [result @(:results node)]
        (push-stack tramp #(listener result)))
      (doseq [result @(:full-results node)]
        (push-stack tramp #(listener result)))
      (not listener-already-exists?)))) 

(defn push-full-listener
  "Pushes a listener into the trampoline's node.
   Schedules notification to listener of all existing full results."
  [tramp node-key listener]
  (let [full-listener-already-exists? (full-listener-exists? tramp node-key)
        node (node-get tramp node-key)
        listeners (:full-listeners node)]
    (when (not (@listeners listener))  ; when listener is not already in listeners
      (add! :push-full-listener)
      (swap! listeners conj listener)
      (doseq [result @(:full-results node)]
        (push-stack tramp #(listener result)))
      (not full-listener-already-exists?)))) 

(defn success [tramp node-key result end]
  (push-result tramp node-key (make-success result end)))

(defn fail [tramp index]  
  (swap! (:failure tramp) (fn [i] (max i index)))) 

;; Stack helper functions

;(defn step
;  "Executes one thing on the stack"
;  [stack]
;  ; It's a little tricky to atomically pop an item off of an atom-based stack
;  ; We need to get down-and-dirty with the low-level compare-and-set! function.
;  ; If step ends up being used from only one thread, this can be simplified.
;  (let [current-stack @stack
;        top (peek current-stack)
;        new-stack (pop current-stack)]
;    (if (and top (compare-and-set! stack current-stack new-stack))
;      (execute top)
;      (recur))))

(defn step
  "Executes one thing on the stack (not threadsafe)"
  [stack]
  (let [top (peek @stack)]
    (swap! stack pop)
    (top)))

(defn run
  "Executes the stack until exhausted"
  [tramp]
  (let [stack (:stack tramp)]
    (while (pos? (count @stack))
      (step stack)
      ;(pprint tramp)
      )))

;; Listeners

; There are three kinds of listeners that receive notifications
; The first kind is a NodeListener which simply listens for a completed parse result
; Takes the node-key of the parser which is awaiting this result.

(defn NodeListener [node-key tramp]
  (fn [result] (push-result tramp node-key result)))

; The second kind of listener is a CatListener which listens at each stage of the
; concatenation parser to carry on the next step.  Think of it as a parse continuation.
; A CatListener needs to know the sequence of results for the parsers that have come
; before, and a list of parsers that remain.  Also, the node-key of the final node
; that needs to know the overall result of the cat parser.

(defn CatListener [results-so-far parser-sequence node-key tramp]
  (fn [result] 
    (let [{parsed-result :result continue-index :index} result
          new-results-so-far (conj results-so-far parsed-result)]
      (if (seq parser-sequence)
        (when (push-listener tramp [continue-index (first parser-sequence)]
                           (CatListener new-results-so-far (next parser-sequence) node-key tramp))
          (push-stack tramp #(-parse (first parser-sequence) continue-index tramp)))
        (push-result tramp node-key (make-success new-results-so-far continue-index))))))

(defn singleton? [s]
  (and (seq s) (not (next s))))

(defn CatFullListener [results-so-far parser-sequence node-key tramp]
  (fn [result] 
    (let [{parsed-result :result continue-index :index} result
          new-results-so-far (conj results-so-far parsed-result)]
      (cond
        (singleton? parser-sequence)
        (when (push-full-listener tramp [continue-index (first parser-sequence)]
                           (CatFullListener new-results-so-far (next parser-sequence) node-key tramp))
          (push-stack tramp #(-full-parse (first parser-sequence) continue-index tramp)))
        
        (seq parser-sequence)
        (when (push-listener tramp [continue-index (first parser-sequence)]
                           (CatFullListener new-results-so-far (next parser-sequence) node-key tramp))
          (push-stack tramp #(-parse (first parser-sequence) continue-index tramp)))
        
        :else
        (push-result tramp node-key (make-success new-results-so-far continue-index))))))

; The top level listener

(defn TopListener [tramp] 
  (fn [result] 
    (swap! (:success tramp) conj result)))

;; Parsers

(defn string-parse
  [this index tramp]
  (let [string (:string this)
        text (:text tramp)
        end (min (count text) (+ index (count string)))
        head (subs text index end)]      
    (if (= string head)
      (success tramp [index this] string end)
      (fail tramp index))))

(defn string-full-parse
  [this index tramp]
  (let [string (:string this)
        text (:text tramp)
        end (min (count text) (+ index (count string)))
        head (subs text index end)]      
    (if (and (= end (count text)) (= string head))
      (success tramp [index this] string end)
      (fail tramp index))))

(defn cat-parse
  [this index tramp]
  (let [parsers (:parsers this)]
    ; Kick-off the first parser, with a CatListener ready to pass the result on in the chain
    ; and with a final target of notifying this parser when the whole sequence is complete
    (when (push-listener tramp [index (first parsers)] (CatListener [] (next parsers) [index this] tramp))
      (push-stack tramp #(-parse (first parsers) index tramp)))))

(defn cat-full-parse
  [this index tramp]
  (let [parsers (:parsers this)]
    ; Kick-off the first parser, with a CatListener ready to pass the result on in the chain
    ; and with a final target of notifying this parser when the whole sequence is complete
    (when (push-listener tramp [index (first parsers)] (CatFullListener [] (next parsers) [index this] tramp))
      (push-stack tramp #(-parse (first parsers) index tramp)))))

(defn alt-parse
  [this index tramp]
  (let [parsers (:parsers this)]
    (doseq [parser parsers]
      (when (push-listener tramp [index parser] (NodeListener [index this] tramp))
        (push-stack tramp #(-parse parser index tramp))))))

(defn alt-full-parse
  [this index tramp]
  (let [parsers (:parsers this)]
    (doseq [parser parsers]
      (when (push-full-listener tramp [index parser] (NodeListener [index this] tramp))
        (push-stack tramp #(-full-parse parser index tramp))))))

(defn non-terminal-parse
  [this index tramp]
  (let [parser (get-parser (:grammar tramp) this)]
    (when (push-listener tramp [index parser] (NodeListener [index this] tramp))
      (push-stack tramp #(-parse parser index tramp)))))

(defn non-terminal-full-parse
  [this index tramp]
  (let [parser (get-parser (:grammar tramp) this)]
    (when (push-full-listener tramp [index parser] (NodeListener [index this] tramp))
      (push-stack tramp #(-full-parse parser index tramp)))))

(def Epsilon {:tag :epsilon})
(defn epsilon-parse
  [index tramp] (success tramp [index Epsilon] nil index))
(defn epsilon-full-parse
  [index tramp] 
  (if (= index (count (:text tramp)))
    (success tramp [index Epsilon] nil index)
    (fail tramp index)))

(def End {:tag :end})
(defn end-parse
  [index tramp] 
  (if (= index (count (:text tramp)))
    (success tramp [index End] nil index)
    (fail tramp index)))
(def end-full-parse end-parse)
    
(defn alt [& parsers] 
  (cond
    (empty? parsers) Epsilon
    (singleton? parsers) (first parsers)
    :else {:tag :alt :parsers parsers}))
(defn cat [& parsers]
  (cond
    (empty? parsers) Epsilon
    (singleton? parsers) (first parsers) ; apply vector reduction
    :else {:tag :cat :parsers parsers}))
(defn string [s] {:tag :string :string s})

(defn parse [grammar parser text]
  (clear!)
  (let [tramp (make-tramp grammar text)]
    (push-full-listener tramp [0 parser] (TopListener tramp))
    (push-stack tramp #(-full-parse parser 0 tramp))
    (run tramp)
    [tramp (if @(:success tramp) @(:success tramp) @(:failure tramp))]))

(def grammar1 {:s (alt (string "a") (string "aa") (string "aaa"))})
(def grammar2 {:s (alt (string "a") (string "b"))})
(def grammar3 {:s (alt (cat (string "a") :s) End)})
(def grammar4 {:y (string "b")
               :x (cat (string "a") :y)})            
(def grammar5 {:s (cat (string "a") (string "b") (string "c"))})
(def grammar6 {:s (alt (cat (string "a") :s) (string "a"))})
(def grammar7 {:s (alt (cat (string "a") :s) Epsilon)})
(def grammar8 {:s (alt (cat (string "a") :s End) (string "a"))})
(def grammar9 {:s (alt (cat (string "a") :s)
                       (cat (string "b") :s)
                       Epsilon)})
(def grammar10 {:s (alt (cat :s (string "a") )
                       (cat :s (string "b") )
                       Epsilon)})
(def grammar11 {:s (alt (cat :s (string "a")) (string "a"))})