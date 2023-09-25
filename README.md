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
application can implement a handler for `IdentityStore` that takes data like
`{:op-type :insert, :record {...}}` and performs an insert for Postgres or MySQL
(or whatever) database.

## Example

To use the donut.service, you:

1. Define a service in your library using the `defservice` macro.
2. Define service functions within the body of `defservice`
3. Use `implement-service` in the client application to define `:substitutions` and
   and a `:handler` for the service. 
   - `:substitutions` is a map of constants within your service definition that
     should get replaced with a client constant; replacing the table name
     `:users` with `:accounts` for example.
   - `:handler` is a function that actually does the real, side-effecting stuff
     necessary for the service to work



## Why not use protocols?
