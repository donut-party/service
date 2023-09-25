(ns examples
  (:require
   [donut.service :as service]
   [clojure.string :as str])
  (:import
   [java.util UUID]))

;;---
;; library portion
;;---
;;
;; You would define your service in the library you're writing. This
;; IdentityStore service implements functions for maintaining a user's identity.

;; defservice is a macro that takes A name for your service, in this case
;; IdentityStore. This service is passed as the first argument to each service
;; function.
(service/defservice IdentityStore

  ;; create-user is an example service function defintion. The map provided is
  ;; used to create a service function that takes two arguments:
  ;; 1. The service
  ;; 2. A "request" body
  ;;
  ;; When you call a service function, it does the following:
  ;; - validates the request body
  ;; - transforms the request, e.g. hashing a password
  ;; - replaces service terms with client terms, e.g. replaces `:users` w/ `:accounts`
  ;; - produces an `op` map, a data description of the action being taken
  ;; - handles the op using the services `:handler`, doing real side-effecting work
  ;; - replaces client terms in the response with service terms
  (create-user
   {:body-schema
    [:map
     [:user/email string?]
     [:user/password string?]]

    :transform-request
    (fn [request]
      (let [password (get-in request [:body :user/password])]
        (update request :body #(-> %
                                   (dissoc :user/password)
                                   (assoc :user/password_hash (str/upper-case password))))))

    :op-template
    {:op-type   :insert
     :container [::term :user]
     :records   [:?]}})

  (create-reset-password-token
   {:body-schema     map?
    :response-schema map?

    :transform-request
    (fn [request]
      (update request :body #(assoc %
                                    :user/password_reset_token (UUID/randomUUID)
                                    :user/password_reset_token_created_at 0)))

    :op-template
    {:op-type   :update
     :container [::term :user]
     :where     {[::term :user/id] [::param :user/id]}
     :record    :?}}))

(def data-store (atom nil))
(defn reset-data-store [f] (reset! data-store nil) (f))

(defmulti identity-store-handler (fn [{:keys [op-type]}] op-type))

(defmethod identity-store-handler
  :insert
  [{:keys [records]}]
  (reset! data-store (first records)))

(defmethod identity-store-handler
  :update
  [{:keys [record]}]
  (swap! data-store merge record))

(service/implement-service IdentityStore
  {:substitutions
   {:user/id :my-user/id
    :user/email :my-user/email
    :user/password_hash :my-user/password_hash
    :user/password_reset_token :my-user/password_reset_token
    :user/password_reset_token_created_at :my-user/password_reset_token_created_at}

   :handler
   identity-store-handler})

(create-user IdentityStore {:user/email "test@test.com" :user/password "test"})
;; =>
#:my-user{:email "test@test.com", :password_hash "TEST"}

;; internal stroage
@data-store ;; =>
#:my-user{:email "test@test.com", :password_hash "TEST"}



(create-reset-password-token IdentityStore {:user/email "test@test.com"})
;; returns:
#:user{:email "test@test.com",
       :password_hash "TEST",
       :password_reset_token #uuid "55c4255e-10d2-4e07-ab3d-febda159b65c",
       :password_reset_token_created_at 0}

;; show "internal" storage
@data-store
#:my-user{:email "test@test.com",
          :password_hash "TEST",
          :password_reset_token #uuid "55c4255e-10d2-4e07-ab3d-febda159b65c",
          :password_reset_token_created_at 0}
