(ns ring.util.response
  "Functions for generating and augmenting response maps."
  (:import java.io.File java.util.Date java.net.URL
           java.net.URLDecoder java.net.URLEncoder)
  (:use [ring.util.time :only (format-date)]
        [ring.util.io :only (last-modified-date)])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn redirect
  "Returns a Ring response for an HTTP 302 redirect."
  [url]
  {:status  302
   :headers {"Location" url}
   :body    ""})

(defn redirect-after-post
  "Returns a Ring response for an HTTP 303 redirect."
  [url]
  {:status  303
   :headers {"Location" url}
   :body    ""})

(defn created
  "Returns a Ring response for a HTTP 201 created response."
  {:added "1.2"}
  ([url] (created url nil))
  ([url body]
     {:status  201
      :headers {"Location" url}
      :body    body}))

(defn not-found
  "Returns a 404 'not found' response."
  {:added "1.1"}
  [body]
  {:status  404
   :headers {}
   :body    body})

(defn response
  "Returns a skeletal Ring response with the given body, status of 200, and no
  headers."
  [body]
  {:status  200
   :headers {}
   :body    body})

(defn status
  "Returns an updated Ring response with the given status."
  [resp status]
  (assoc resp :status status))

(defn header
  "Returns an updated Ring response with the specified header added."
  [resp name value]
  (assoc-in resp [:headers name] (str value)))

(defn- safe-path?
  "Is a filepath safe for a particular root?"
  [^String root ^String path]
  (.startsWith (.getCanonicalPath (File. root path))
               (.getCanonicalPath (File. root))))

(defn- directory-transversal?
  "Check if a path contains '..'."
  [^String path]
  (-> (str/split path #"/|\\")
      (set)
      (contains? "..")))

(defn- find-index-file
  "Search the directory for an index file."
  [^File dir]
  (first
    (filter
      #(.startsWith (.toLowerCase (.getName ^File %)) "index.")
       (.listFiles dir))))

(defn- safely-find-file [^String path opts]
  (if-let [^String root (:root opts)]
    (if (or (safe-path? root path)
            (and (:allow-symlinks? opts) (not (directory-transversal? path))))
      (File. root path))
    (File. path)))

(defn- find-file [^String path opts]
  (if-let [^File file (safely-find-file path opts)]
    (cond
      (.isDirectory file)
        (and (:index-files? opts true) (find-index-file file))
      (.exists file)
        file)))

(defn- file-data [^File file]
  {:content        file
   :content-length (.length file)
   :last-modified  (last-modified-date file)})

(defn- content-length [resp len]
  (if len
    (header resp "Content-Length" len)
    resp))

(defn- last-modified [resp last-mod]
  (if last-mod
    (header resp "Last-Modified" (format-date last-mod))
    resp))

(defn file-response
  "Returns a Ring response to serve a static file, or nil if an appropriate
  file does not exist.
  Options:
    :root            - take the filepath relative to this root path
    :index-files?    - look for index.* files in directories, defaults to true
    :allow-symlinks? - serve files through symbolic links, defaults to false"
  [filepath & [opts]]
  (if-let [file (find-file filepath opts)]
    (let [data (file-data file)]
      (-> (response (:content data))
          (content-length (:content-length data))
          (last-modified (:last-modified data))))))

;; In Clojure versions 1.2.0, 1.2.1 and 1.3.0, the as-file function
;; in clojure.java.io does not correctly decode special characters in
;; URLs (e.g. '%20' should be turned into ' ').
;;
;; See: http://dev.clojure.org/jira/browse/CLJ-885
;;
;; In Clojure 1.5.1, the as-file function does not correctly decode
;; UTF-8 byte sequences.
;;
;; See: http://dev.clojure.org/jira/browse/CLJ-1177
;;
;; As a work-around, we'll backport the fix from CLJ-1177 into
;; url-as-file.

(defn- ^File url-as-file [^java.net.URL u]
  (-> (.getFile u)
      (str/replace \/ File/separatorChar)
      (str/replace "+" (URLEncoder/encode "+" "UTF-8"))
      (URLDecoder/decode "UTF-8")
      io/as-file))

(defn content-type
  "Returns an updated Ring response with the a Content-Type header corresponding
  to the given content-type."
  [resp content-type]
  (header resp "Content-Type" content-type))

(defn charset
  "Returns an updated Ring response with the supplied charset added to the
  Content-Type header."
  {:added "1.1"}
  [resp charset]
  (update-in resp [:headers "Content-Type"]
    (fn [content-type]
      (-> (or content-type "text/plain")
          (str/replace #";\s*charset=[^;]*" "")
          (str "; charset=" charset)))))

(defn set-cookie
  "Sets a cookie on the response. Requires the handler to be wrapped in the
  wrap-cookies middleware."
  {:added "1.1"}
  [resp name value & [opts]]
  (assoc-in resp [:cookies name] (merge {:value value} opts)))

(defn response?
  "True if the supplied value is a valid response map."
  {:added "1.1"}
  [resp]
  (and (map? resp)
       (integer? (:status resp))
       (map? (:headers resp))))

(defmulti resource-data
  "Returns data about the resource specified by url, or nil if an
  appropriate resource does not exist.

  The return value is a map with optional values for:
  :content        - the content of the URL, suitable for use as the :body
                    of a ring response
  :content-length - the length of the :content, nil if not available
  :last-modified  - the Date the :content was last modified, nil if not
                    available

  This dispatches on the protocol of the URL as a keyword, and
  implementations are provided for :file and :jar. If you are on a
  platform where (Class/getResource) returns URLs with a different
  protocol, you will need to provide an implementation for that
  protocol.

  This function is used internally by url-response."
  (fn [^java.net.URL url]
    (keyword (.getProtocol url))))

(defmethod resource-data :file
  [url]
  (if-let [file (url-as-file url)]
    (if-not (.isDirectory file)
      (file-data file))))

(defn- add-ending-slash [^String path]
  (if (.endsWith path "/")
    path
    (str path "/")))

(defn- jar-directory? [^java.net.JarURLConnection conn]
  (let [jar-file   (.getJarFile conn)
        entry-name (.getEntryName conn)
        dir-entry  (.getEntry jar-file (add-ending-slash entry-name))]
    (and dir-entry (.isDirectory dir-entry))))

(defn- connection-content-length [^java.net.URLConnection conn]
  (let [len (.getContentLength conn)]
    (if (<= 0 len) len)))

(defn- connection-last-modified [^java.net.URLConnection conn]
  (let [last-mod (.getLastModified conn)]
    (if-not (zero? last-mod)
      (Date. last-mod))))

(defmethod resource-data :jar
  [^java.net.URL url]
  (let [conn (.openConnection url)]
    (if-not (jar-directory? conn)
      {:content        (.getInputStream conn)
       :content-length (connection-content-length conn)
       :last-modified  (connection-last-modified conn)})))

(defn url-response
  "Return a response for the supplied URL."
  {:added "1.2"}
  [^URL url]
  (if-let [data (resource-data url)]
    (-> (response (:content data))
        (content-length (:content-length data))
        (last-modified (:last-modified data)))))

(defn resource-response
  "Returns a Ring response to serve a packaged resource, or nil if the
  resource does not exist.
  Options:
    :root - take the resource relative to this root"
  [path & [opts]]
  (let [path (-> (str (:root opts "") "/" path)
                 (.replace "//" "/")
                 (.replaceAll "^/" ""))]
    (if-let [resource (io/resource path)]
      (url-response resource))))

(defn get-header
  "Look up a header in a Ring response (or request) case insensitively,
  returning the value of the header."
  {:added "1.2"}
  [resp ^String header-name]
  (some (fn [[k v]] (if (.equalsIgnoreCase header-name k) v))
        (:headers resp)))
