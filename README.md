# Baku

## Overview

**Baku** is a Tapir extension library that allows you to easily isolate your API definitions from server and security logic for cleaner, more maintainable code. This makes it simple to share contracts across microservices without exposing the underlying implementation.

## Installation

SBT:

```scala
libraryDependencies += "io.github.arkida39" %% "baku" % "<version>"
```

Mill:

```scala
ivy"io.github.arkida39::baku:<version>"
```

> _replace `version` with the version of **Baku**. Each **Baku** release has a version in format: `<tapir version>.<baku version>`, for example `1.12.4.0` is made for Tapir version `1.12.4`._

## Usage

### The Contract

Define these in a standalone module. This allows you to publish the artifact to a repository so consumers can import your API without the server logic or its heavy dependencies.

#### 1. Define a Contract trait

```scala
case class User(token: String)
case class BarInput(id: Int, name: String)
trait MyContract extends Contract {
    val foo: PublicEndpoint[String, Unit, String, Any]
    val bar: PublicEndpoint[BarInput, Unit, String, Any]
    val baz: SecureEndpoint[String, User, BarInput, Unit, String, Any]
}
```

> _for more information about what each generic argument does, refer to `Contract` trait._

#### 2. Define a Resource object (endpoint definitions)

```scala
object MyResource extends MyContract, Resource {

    import sttp.tapir.*

    override val foo = endpoint.get.in("foo").in(query[String]("name"))
        .out(stringBody)
    override val bar = endpoint.get.in("bar").in(query[Int]("id"))
        .in(query[String]("name")).mapInTo[BarInput].out(stringBody)

    override val baz = endpoint.get.in("baz").in(query[Int]("id"))
        .in(query[String]("name")).mapInTo[BarInput].out(stringBody)
        .securityIn(auth.bearer[String]())

}
```

### The Implementation

Define these in a private server module. This allows you to wire up heavy dependencies—like database drivers and security providers—while keeping them completely hidden from the consumers of your API.

#### 3. Define a Service object (server and security logic)

```scala
object MyService extends MyContract, Service[Identity] {

    override val foo = (name: String) => Right(s"[FOO] Hello $name")

    override val bar = (input: BarInput) =>
        Right(s"[BAR] Name: ${input.name}; Id: ${input.id.toString()}")

    override val baz = securityLogic[String, User, Unit](_ =>
        Right(User("secrettoken")),
    ).serverLogic(user =>
        bar => Right(s"[BAZ] Name: ${bar.name}; Token: ${user.token}"),
    )

}
```

> _instead of `Identity`, you can use your desired wrapper effect, making your API definitions portable across ZIO, Cats Effect, etc._

#### 4. Wire the Resource and the Service in a Component

```scala
val myComponent = Component.of[MyContract, Identity](MyResource, MyService)
```

> _this is a macro that uses experimental reflection features, so you would need to use `@experimental` annotation for it to work._

Now you can use this `Component` to extract the individual wired endpoints, e.g.:

```scala
myComponent.foo // val foo: ServerEndpoint[Any, Identity]{type SECURITY_INPUT = Unit; type PRINCIPAL = Unit; type INPUT = String; type ERROR_OUTPUT = Unit; type OUTPUT = String}
```

Or to get the list of all the wired endpoints (in the order they were declared in your `Contract`):

```scala
myComponent.all // val all: List[ServerEndpoint[Any, Identity]
```

> _`all` retains (and combines) the capabilities (Tapir's `R` generic argument) of all endpoints declared in the `Contract`, so, for example, if one of your endpoints requires a `ZioStreams` capability, and the other has an `Fs2Streams[IO]` capability, the type will be as follows: `val all: List[ServerEndpoint[ZioStreams & Fs2Streams[IO], Identity]`._
