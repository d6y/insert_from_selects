Exploring two styles of insert that require a look up of identifiers.

Described in the two source files, _example1.scala_ and _example2.scala_.

Example1 is my attempt at an `insert ... select ('foo', select(...), select(...))` style query. The trick in that code with `max` seem a bit stinky.

Example2 is made up of three distinct queries.

In both cases we take data describing a sales event:

```
   SalesEvent("Product name", Some("Customer name"), Some("Assistant name"))
```

...and try to lookup the customer and assistant.

You can run the code from sbt with `runMain Example1` and `runMain Example2`.

The console output shows the queries and the final state of the database, which should be:

```
SalesLog(abacus,Some(UserId(1)),None)
SalesLog(bagpipes,None,Some(UserId(1)))
SalesLog(catapult,None,None)
SalesLog(desk lamp,Some(UserId(1)),Some(UserId(1)))
SalesLog(elbow grease,Some(UserId(1)),None)
```

Relates to: [a gitter conversation](https://gitter.im/slick/slick?at=5964b3c489aea4761d88a824).

