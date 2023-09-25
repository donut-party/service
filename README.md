[![Clojars Project](https://img.shields.io/clojars/v/party.donut/service.svg)](https://clojars.org/party.donut/service)

# donut.service (experimental)

Lets you write libraries that rely on virtual services which clients can
implement.

## Rationale

Imagine you want to write a user identity management library that handles user
signup and authentication. This library has to perform CRUD operations with some
kind of data store to manage and query user records. It must handle tasks like
hashing passwords, generating password reset tokens, and resetting passwords.

How do you get this done in Clojure? There are two issues you need to deal with:

- The data shape your library uses might not match the client's expectation.
  Where you're using `:user/password`, the client is using `:accounts/password`.
- You don't know what data store the client uses.

The general problem is that the library you want to build depends on an external
service that a client uses too, but the library can't hard code details for
interacting with that service because those details are up to the client. If
your library uses next.jdbc and the client is using a datastore without jdbc
support, someone's going to have a bad time.

donut.service lets you define a virtual service that you can use to create your
library. Your library calls the interface the virtual service provides, and then
developers implement the virtual service in their applications so that it works
for their environment.

For example, your identity management library can define a virtual
`IdentityStore` service that handles CRUD operations for user data. A client
application can implement a _handler_ for `IdentityStore` that takes data like
`{:op-type :insert, :record {...}}` and performs an insert for Postgres or MySQL
(or whatever) database.

## Example

``` clojure
(ns examples
  (:require
   [donut.service :as service]
   [clojure.string :as str]))

;;---
;; library portion - you would define your service in the library you're writing
;;---
(service/defservice IdentityStore
  ;; this defines a service function named create-user. service functions always
  ;; take two arguments, a service and a body
  (create-user
   {;; validate input
    :body-schema
    [:map
     [:user/email string?]
     [:user/password string?]]

    ;; described below
    :transform-request
    (fn [request]
      (let [password-hash (-> request
                              (get-in [:body :user/password])
                              str/upper-case)]
        (update request :body #(-> %
                                   (dissoc :user/password)
                                   (assoc :user/password_hash password-hash)))))

    ;; this is used to construct the argument that's sent to a service handler
    :op-template
    {:op-type   :insert
     :container [::service/term :user]
     :records   [::service/body]}}))

;;---
;; client portion - people using your lib would have code like this in their app
;;---

;; using an atom for the purpose of this example so we can inspect it
(def data-store (atom nil))

(defmulti identity-store-handler (fn [{:keys [op-type]}] op-type))
(defmethod identity-store-handler
  :insert
  [{:keys [records]}]
  (reset! data-store (first records)))

(service/implement-service IdentityStore
  {:substitutions
   {:user/email :my-user/email
    :user/password_hash :my-user/password_hash}

   :handler
   identity-store-handler})

;; now let's actually call the create-user function. It should update the
;; @data-store atom, and it should return a map
(create-user IdentityStore {:user/email "test@test.com" :user/password "test"})
;; =>
#:user{:email "test@test.com", :password_hash "TEST"}

@data-store ;; =>
#:my-user{:email "test@test.com", :password_hash "TEST"}
```

Let's walk through this.

### Library: define a service

You use the `defservice` macro to define your service. A service is comprised of:

- A `Service` record
- Service functions

Internally, the `defservice` macro generates code like `(def IdentityStore
(map->Service {:api ...})`. The `Service` record type is defined by
`donut.service` and right now there isn't anything significant about it; it
doesn't implement any protocols or anything like that.

Service functions are defined with forms like:

``` clojure
(create-user
 {:body-schema ...
  :transform-request ...
  :op-template ...})
```

These keys are described in detail below. The higher-level idea here is that the
`defservice` macro uses these service function definitons to define functions
using `defn`. Service functions take two arguments: a service, and a body. They
perform a series of transformations to create an `op` map, and then pass that as
an argument to the _handler_ provided by the client application.

### Client: implement service

`implement-service` updates a service with `:substitutions` and a `:handler`.

**`:substitutions`** is used to translate between the service's representations, and
the client applications. For example, the service might model user data as
`:user/email` but the client application might model it as `:account/email`. The
`:substitutions` map is also used to replace _terms_ in service function _op
templates_ -- more on that below.

**`:handler`** is the function used to actually do real work, like interact with
a real database or send real HTTP api calls.

### Example usage

In the code snippet above, we have:

``` clojure
;; now let's actually call the create-user function. It should update the
;; @data-store atom, and it should return a map
(create-user IdentityStore {:my-user/email "test@test.com" :my-user/password "test"})
;; =>
#:user{:email "test@test.com", :password_hash "TEST"}

@data-store ;; =>
#:my-user{:email "test@test.com", :password_hash "TEST"}
```

Notice that the argument passed to `create-user` has the keys `:my-user/email`
and `:my-user/password`, but the return value has the keys `:user/email` and
`:user/password_hash`. There are a couple things going on here:

1. The substitutions map is used to translate between the client's
   representation and the services
2. In the service function definition, `:transform-request` updates request's
  `:body`, removing the `:user/password` key and adding a `:user/password_hash`
  key, which is just the password upper-cased for this example.

When you deref `data-store`, notice that the keys stored are the _client_ keys
(`:my-user/email`), not the service keys. 

## Usage

### service functions

Service functions are defined like:

``` clojure
(service-function-name
 {:body-schema malli-schema
  :response-schema malli-schema ;; not implemented
  :transform-request fn 
  :op-template map})
```

This configuration works in conjunction with the services `:substitutions` and
`:handler` to drive the behavior of the service function.

`donut.service` terminology draws a bit on the metaphor of a service as an HTTP
server. Calling a service function is like sending a request. With an HTTP
request, you have a body to convey data to the server. The word _body_ is used
to refer to the data you include when you call a service function.

When you call a service function, it does the following:

- translates from the client representation to the service's using `:substitutions`
- validates the request _body_ using `:body-schema`.
- transforms the request using `:transform-request`. You use this to e.g. hash a
  password
- uses `:op-template` to produce an `op` map, a data description of the action
  to take
- handles the op using the service's `:handler`, doing real side-effecting work
- translates the result from the client's representation to the service's. This
  is because right now my thinking is that the service functions are unlikely to
  be called directly by client code. Rather, they're meant to be called
  internally by the library which defines the service.

### ops and op templates

A service function produces an op map (or just _op_) which gets passed to the
service's `:handler`. An op describes stateful actions like CRUD interactions.
For example, here's an op to insert a record:

``` clojure
{:op-type :insert
 :container :account
 :records [{:account/email "test@test.com"}]}
```

A service function's `:op-template` is used to produce these ops. Here's an op
template that would produce the above op:

``` clojure
{:op-type :insert
 :container [::service/term :user]
 :records [::service/body]}
```

`[::service/term k]`, `::service/body`, and `[::service/param k]` (not shown)
are used to replace values in the template with values from the service's
`:substition` map and from the `body` passed to the service function call.

**`[::service/term k]`** if your service's substition map includes `k`, then its
client value will be used. For example, if your op template has
`[::service/term :user]` and your service's `:substition` is `{:user :account}`,
then `:account` will be used instead of `:user`.

**`::service/body`** this will get replaced with the body.

**`[::service/param]`** a form like `[::service/param k]` gets replaced
internally with something like `(k body)`. So for example if you want to update
a record, you might have an `:op-template` like this:

``` clojure
{:op-type   :update
 :container [::service/term :user]
 :where     {[::service/term :user/id] [::service/param :user/id]}
 :record    ::service/body}
```

This will use `(:user/id body)` to get the needed value.

### handlers

Handlers take one argument, an op map, and do work.

Eventually I think it should be possible to create generic handlers, like a JDBC
handler. I'd like donut.service to ship with some handlers that can handle the
majority of use cases.

## Why not use protocols?

TODO

## Experimental

This library is an experiment. It will probably change and may get abandoned.
I'm putting it out to collaborate with folks on it, to see if it's a worthwhile
idea.

PRs and issues welcome! Also check out the [#donut channel in Clojurians
Slack](https://clojurians.slack.com/archives/C030C4Z2W0Y) if you wanna chat or
if you have questions.
