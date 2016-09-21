(ns org.domaindrivenarchitecture.config.commons.xml-tools
  (:require [clojure.xml :as xml]
            [clojure.pprint]
            [clojure.java.io]
            [pallet.core.data-api :as da]))

(import java.io.StringWriter)

(defn- pprint-to-string [m]
  (let [w (StringWriter.)] (clojure.pprint/pprint m w)(.toString w)))

(defn- xml-str [obj]
  "Outputs a string with xml-escape of characters <,> and &"
  (clojure.string/escape 
    (str (or obj "NIL"))
    {\< "&lt;", \> "&gt;", \& "&amp;"}))

(defn- xml-cljmap
  "Converts a clojure map to xml structure recursively."
  [cljmap]
  (cond 
    (map? cljmap)
    (struct 
      xml/element 
      :map {:type "Map"} 
      (reduce-kv 
        (fn [vec k v] 
          (conj vec (struct xml/element :key {:name (xml-str k)} [(xml-cljmap v)]))) 
        '() cljmap))
    
    (coll? cljmap)
    (struct xml/element 
     :map {:type "Collection"} 
     (reduce
       (fn [c e]
	        (conj c (struct xml/element :key {} [(xml-cljmap e)])))
	      [] cljmap))   
    
    true
    (xml-str cljmap)
  ))

(defn- xml-phases
  "Converts :phases information from session to xml"
  [session-data]
  (let [phases (remove #{:pallet/os} (da/phase-seq session-data))]
    (struct xml/element 
      :phases {}
      (reduce
        (fn [elements phase]
	         (conj elements (struct xml/element :phase {} [(name phase)])))
	       [] phases))))

(defn- xml-groups
  "Converts groups information from session to xml"
  [session-data]
  (let [groups (da/groups session-data)]
    (struct xml/element 
      :groups {}
      (reduce
        (fn [elements group]
	         (conj elements (struct xml/element :group {} [(name group)])))
	       [] groups))))

(defn- xml-action-script
  "Converts output of the script of an action to xml."
  [script]
  (cond
    ; If script is a string, the action got executed and this just needs escaping
    (string? script)
    [(xml-str script)]
    
    ; If script is a vector the first element contains information and the second is the script
    ; In this case script needs conversion ("\n" -> newline)
    (vector? script)
    [(xml-str (first script)) 
     "\n\n"   
     (xml-str (clojure.string/replace (second script) "\\n" "\n"))]
    
    ; Else just try to convert to string
    true
    [(xml-str script)]
    ))

(defn- xml-action-result
  "Converts an action-result to xml after executing it."
  [{:keys [script out exit error action-symbol context summary] :as action-result}]
  (if
    (nil? script)
    (struct xml/element
	    :action-result {}
	    [(struct xml/element :action-symbol {} ["Other Action (Maybe local/logging?)"])
       (struct xml/element :details {} [(xml-cljmap action-result)])
	     ])
	  (struct xml/element
	    :action-result {}
	   (filter #(not (nil? %))
      [(struct xml/element :script {} (xml-action-script script))
	      (if-not (nil? out) (struct xml/element :out {} [(xml-str out)]))
	      (if-not (nil? exit) (struct xml/element :exit {} [(xml-str exit)]))
	      (if-not (nil? action-symbol) (struct xml/element :action-symbol {} [(xml-str action-symbol)]))
	      (if-not (nil? context) (struct xml/element :context {} [(xml-str context)]))
	      (if-not (nil? summary) (struct xml/element :summary {} [(xml-str summary)]))
       (struct xml/element :details {} [(xml-cljmap action-result)])
	      ]))))

(defn- xml-node-actions
  [run]
  (struct xml/element
    :node-actions {:phase (-> run :phase name) 
                   :group (-> run :group-name name)
                   :node (-> run :node :primary-ip)}
    (reduce
      (fn [elements action-result]
	      (if (nil? action-result)
         elements
         (conj elements (xml-action-result action-result))))
	    [] (-> run :action-results))))

(defn- xml-runs
  [session-data]
  (let [runs (-> session-data :runs)]
    (struct xml/element
      :runs {}
      (reduce
        (fn [elements run]
	        (conj elements (xml-node-actions run)))
	      [] runs))))

(defn explain-session-xml
  "Transform a session result (after pallet.api/lift or pallet.api/converge) into an XML structure.
   Use emit-xml(-to-string/file) to print the resulting xml file."
  [session]
  (let [session-data (if (:runs session) session (da/session-data session))]
    (struct 
      xml/element :session {} 
      [(struct xml/element :session-data-map {} [(xml-cljmap session)])
       (xml-phases session-data) 
       (xml-groups session-data) 
       (xml-runs session-data)]
    )))

(defn explain-plan-xml
  "Explains the actions created from a plan-function using a mock without executing the actions."
  [plan-fn]
  (explain-session-xml
    (:session (da/explain-plan plan-fn ["mock-node" "mock-group" "0.0.0.0" :ubuntu]))))

(defn emit-xml-to-stdout [xml]
  (println "<?xml version='1.0' encoding='UTF-8'?>")
  (println "<?xml-stylesheet type='text/xsl' href='session.xsl'?>")
  (xml/emit-element xml))

(defn emit-xml-to-string [xml]
  (with-out-str (emit-xml-to-stdout xml)))

(defn emit-xml-to-file [f xml]
  "Writes xml to file and puts the xsl to the same directory."
  (spit f (emit-xml-to-string xml))
  (spit 
    (str (.getParent (clojure.java.io/file f)) "/session.xsl") 
    (slurp (clojure.java.io/resource "session.xsl")))
  )
