(ns pl.danieljanus.tagsoup
  (:require [clojure.zip :as zip]
            [clojure.xml :as xml]
            [clojure.contrib.lazy-xml :as lazy-xml])
  (:import (org.ccil.cowan.tagsoup Parser)
           (java.net URI URL MalformedURLException Socket)
           (java.io InputStream File FileInputStream ByteArrayInputStream BufferedInputStream InputStreamReader BufferedReader)
           (org.xml.sax InputSource)))

(defn- attributes-map
  "Converts an Attributes object into a Clojure map,"
  [attrs]
  (into {}
        (map #(vector (keyword (.getQName attrs %)) (.getValue attrs %)) (range (.getLength attrs)))))

(defn tag
  "Returns the tag name of a given HTML tree node as a keyword."
  [node]
  (first node))

(defn attributes
  "Returns the attributes of a given HTML tree node as a Clojure map."
  [node]
  (second node))

(defn children
  "Returns a seq of children nodes of a given HTML tree node."
  [node]
  (rest (rest node)))

(defn- encoding-from-content-type
  "Strips the character-set name from a Content-Type: HTTP header value."
  [content-type]
  (when content-type
    (second (re-find #"charset=(.*)$" (.toLowerCase content-type)))))

(defmulti #^{:doc "Like clojure.contrib.duck-streams/reader, but
  attempts to convert its argument to an InputStream. Returns a map
  mapping :stream to the stream and, potentially, :encoding to the
  encoding detected on that stream."}
  input-stream class)

(defmethod input-stream InputStream [#^InputStream x]
  {:stream x})

(defmethod input-stream File [#^File x]
  {:stream (FileInputStream. x)})

(defmethod input-stream URL [#^URL x]
  (if (= "file" (.getProtocol x))
    (FileInputStream. (.getPath x))
    (let [connection (.openConnection x)]
      {:stream (.getInputStream connection), :encoding (-> connection (.getHeaderField "Content-Type") encoding-from-content-type)})))

(defmethod input-stream URI [#^URI x]
  (input-stream (.toURL x)))

(defmethod input-stream String [#^String x]
  (try (let [url (URL. x)]
         (input-stream url))
       (catch MalformedURLException e
         (input-stream (File. x)))))

(defmethod input-stream Socket [#^Socket x]
  {:stream (.getInputStream x)})

(defmethod input-stream :default [x]
  (throw (Exception. (str "Cannot open " (pr-str x) " as an input stream."))))

(defn read-xml-encoding-declaration
  "Reads XML encoding declaration from a BufferedInputStream."
  [stream]
  (let [arr-size 1024
        arr (make-array Byte/TYPE arr-size)]
    (.mark stream arr-size)
    (loop [offset 0]
      (let [nread (.read stream arr offset (- arr-size offset))]
        (if (or (= nread -1) (= (+ offset nread) arr-size))
          arr
          (recur (+ offset nread)))))
    (.reset stream)
    (let [s (String. arr (java.nio.charset.Charset/forName "ISO-8859-1"))]
      (when (.startsWith s "<?xml ")
        (second (re-find #"encoding=\"(.*?)\"" s))))))

(defn- startparse-tagsoup
  "A startparse function compatible with clojure.xml."
  [source content-handler]
  (let [p (Parser.)
        stream (-> ((input-stream source) :stream) BufferedInputStream.)
        source (InputSource. stream)
        xml-encoding (read-xml-encoding-declaration stream)]
    (when xml-encoding
      (.setEncoding source xml-encoding))
    (.setContentHandler p content-handler)
    (.parse p source)
    p))

(defn parse
  "Parses a file or HTTP URL.  file may be anything that can be fed
to clojure.contrib.duck-streams/reader.  If strip-whitespace is true
removes empty (whitespace-only) PCDATA from in between the tags, which
makes the resulting tree cleaner. If prefer-header-http-info is true
and the encoding is specified in both <meta http-equiv> tag and the
HTTP headers (in this case, input must be a URL or a string
representing one), the latter is preferred."
  [input & {:keys [xml strip-whitespace prefer-header-http-info], :or {strip-whitespace true}}]
  (with-local-vars [tree (zip/vector-zip []) pcdata "" reparse false]
    (let [{:keys [stream encoding]} (input-stream input)
          stream (BufferedInputStream. stream)
          source (InputSource. stream)
          reparse-exception (Exception. "reparse")
          xml-encoding (when xml (read-xml-encoding-declaration stream))
          _ (.mark stream 65536)
          _ (.setEncoding source (or (and xml xml-encoding) encoding))
          flush-pcdata #(let [data (var-get pcdata)]
                          (when-not (empty? data)
                            (when-not (and strip-whitespace (re-find #"^\s+$" data))
                              (var-set tree (-> tree var-get (zip/append-child data))))
                            (var-set pcdata "")))
          parser (proxy [Parser] []
                   (pcdata [buf offset length]
                     (var-set pcdata (str (var-get pcdata) (String. buf offset length))))
                   (startElement [uri localname qname attrs]
                     (flush-pcdata)
                     (let [attrs (attributes-map attrs)
                           tag (keyword localname)]
                       (when (and (= tag :meta)
                                  (let [http-equiv (attrs :http-equiv)]
                                    (and http-equiv (= (.toLowerCase http-equiv) "content-type"))))
                         (let [charset (encoding-from-content-type (attrs :content))]
                           (when (and charset
                                      (not (and encoding prefer-header-http-info))
                                      (not (var-get reparse)))
                             (.setEncoding source charset)
                             (var-set reparse true)
                             (.reset stream)
                             (throw reparse-exception))))
                       (var-set tree (-> tree var-get
                                         (zip/append-child [])
                                         (zip/down)
                                         (zip/rightmost)
                                         (zip/append-child tag)
                                         (zip/append-child attrs)))))
                   (endElement [uri localname qname]
                     (flush-pcdata)
                     (var-set tree (-> tree var-get zip/up))))]
      (try
       (.parse parser source)
       (catch Exception e
         (if (= e reparse-exception)
           (do
             (var-set pcdata "")
             (var-set tree (zip/vector-zip []))
             (.parse parser source))
           (throw e))))
      (first (remove string? (zip/root (var-get tree)))))))

(defn parse-string
  "Parses a given string as HTML, passing options to `parse'."
  [s & options]
  (apply parse (-> s .getBytes ByteArrayInputStream.) options))

(defn parse-xml
  "Parses a given XML using TagSoup and returns the parse result
in the same format as clojure.xml/parse."
  [input]
  (xml/parse input startparse-tagsoup))

(defn lazy-parse-xml
  "Parses the XML using TagSoup and as result, returns a lazy sequence of elements in the same format as clojure.contrib.lazy-xml/parse-seq"
  [input]
  (lazy-xml/parse-seq input startparse-tagsoup))