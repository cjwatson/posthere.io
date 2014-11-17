(ns posthere.capture-request
  "Capture the request to a particular URL in the storage so they can be retrieved later."
  (:require [clojure.core.incubator :refer (dissoc-in)]
            [ring.util.codec :refer (form-decode)]
            [ring.util.response :refer (header response status)]
            [posthere.storage :refer (save-request)]
            [clj-time.core :as t]))

(def max-body-size (* 1024 1024)) ; 1 megabyte

(def form-urlencoded "application/x-www-form-urlencoded")

(def default-http-status-code 200)

;; http://en.wikipedia.org/wiki/List_of_HTTP_status_codes
(def http-status-codes #{
  100 101 102
  200 201 202 203 204 205 206 207 208 226
  300 301 302 303 304 305 306 307 308
  400 401 402 403 404 405 406 407 408 409 410 411 412 413 414 415 416 417 418 419 420 
  422 423 424 426 428 429 431 440 449 450 451 494 495 496 497 498 499
  500 501 502 503 504 505 506 507 508 509 510 511 520 521 522 523 524 598 599
})

(defn post-response-body [url-uuid]
  (str "We got your POST request! View your results at: http://posthere.io/" url-uuid "\n"))

(defn- post-response
  ""
  [url-uuid request]
  (-> (post-response-body url-uuid) ; string with directions
    (response) ; make the string the body of our response
    (header "Content-Type" "text/plain") ; add a content type header
    (status (:status request)))) ; add the HTTP status of the response

(defn- update-request-body-too-big [uuid request-hash]
  (save-request 
    uuid 
    (assoc (dissoc request-hash :body) :body-overflow true)))

(defn- add-time-stamp [request] 
  (assoc request :timestamp (str (t/now))))

(defn- content-length-OK?
  "Check if the content length header is < max-body-size"
  [request]
  (let [content-length-header (read-string (get-in request [:headers "content-length"])) ; content-length header as int
        content-length (or content-length-header 0)] ; 0 if we have no content-length header
    (< content-length max-body-size)))

;; TODO check body length after slurp
;; TODO set overflow flags
(defn- limit-body-request-size
  ""
  [request]
  (if (and (:body request) (content-length-OK? request))
    (assoc request :body (slurp (:body request)))
    (dissoc request :body)))

(defn- parse-query-string
  ""
  [request]
  (if-let [query-string (:query-string request)]
    (assoc request :parsed-query-string (form-decode query-string))
    request)) ; no query-string to parse

;; TODO code this defensively in case the content-type lied
(defn- parse-form-fields
  "Parse the form fields string into a map if the content type indicates the body is form encoded."
  [request]
  (if (= (:content-type request) form-urlencoded)
    (assoc request :parsed-body (form-decode (:body request)))
    request))

(defn- valid-status
  "Esure the requested status is an integer in our set of valid HTTP statuses,
   default the status if it's not or none was requested."
  [requested-status]
  (let [status (read-string (or requested-status "-1"))] ; turn status string into an integer
    (if (contains? http-status-codes status) ; make sure it's in our valid set
      status
      default-http-status-code)))

(defn- handle-response-status
  "Move the requested status from the query string params to the root of the request map."
  [request]
  (let [requested-status-path [:parsed-query-string "status"]
        requested-status (get-in request requested-status-path)
        status (valid-status requested-status)] ; ensure the requested status is valid
    (-> request
      (assoc :status status)
      (dissoc-in requested-status-path))))

(defn capture-request
  "Save the processed request, respond to the POST."
  [url-uuid request]
  ;; Process the request
  (let [processed-request 
    (-> request
      (add-time-stamp)
      (limit-body-request-size)
      (parse-query-string)
      (parse-form-fields)
      (handle-response-status))]
    ;; Save the request
    (save-request url-uuid processed-request)
    ;; Respond to the HTTP client
    (post-response url-uuid processed-request)))