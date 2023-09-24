[![Clojars Project](https://img.shields.io/clojars/v/party.donut/service.svg)](https://clojars.org/party.donut/service)

# donut.service (experimental)

Define virtual services for libraries which clients can implement.

## Rationale

Imagine you want to write a user identity management library that handles user
signup and authentication. This library has to perform CRUD operations on some
kind of data store to manage and query user records. It must handle tasks like
hashing password, generating password reset tokens, and resetting passwords.

How do you get this done in Clojure? There are two issues you need to deal with:

- The data shape your library uses might not match the client's expectation.
  Where you're using `:user/password`, the client is using `:users/password`.
- You don't know what data store the client uses.

The general problem is that the library you want to build depends on an external
service, but you don't know exactly how to interact with that service.

This library, donut.service, lets you define a virtual service that you can use
to create your library. Developers can then implement the virtual service in
their applications so that it works for their environment.

For example, your identity management library can define a virtual
`IdentityStore` service handles CRUD operations for user data. A client
application can implement a handler for `IdentityStore` that takes data like
`{:op-type :insert, :record {...}}` and performs an insert for Postgres or MySQL
(or whatever) database.

## Example

## Why not use protocols?
