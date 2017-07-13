import slick.jdbc.H2Profile.api._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

/*
  This example generates SQL that looks like:
 
Insert into "sales_log" ("product_name","customer_id","assistant_id")
  select 'desk lamp', cast(x2.x3 as BIGINT), cast(x4.x5 as BIGINT) from 
     (select max("id") as x3 from "app_users" where "name" = 'Alice') x2,
     (select max("id") as x5 from "app_users" where "name" = 'Alice') x4

I find it hard to follow compared to Example2

 */
object Example1 extends App {

  // Let's pretend we receive mesasges from a sales system.
  // There's always a product, but sometimes the customer is anonymous.
  // And sometimes there is a sales assitant who gets commission, and sometimes there's not.
  final case class SaleEvent(
    productName   : String,
    customerName  : Option[String],
    assistantName : Option[String]
  )

  // We want to create a log of these sales, translating the customer and sales assisstant into user IDs.
  case class CustomerId(value: Long) extends AnyVal with MappedTo[Long]
  case class StaffId(value: Long) extends AnyVal with MappedTo[Long]

  final case class SalesLog(
    productName    : String,
    customer       : Option[CustomerId],
    salesAssistant : Option[StaffId]
  )


  // Here's the table defintion for the records we want to store:
  final class LogTable(tag: Tag) extends Table[SalesLog](tag, "sales_log") {
    def productName = column[String]("product_name")
    def customerId  = column[Option[CustomerId]]("customer_id")
    def assistantId = column[Option[StaffId]]("assistant_id")

    def * = (productName, customerId, assistantId).mapTo[SalesLog]
  }

  lazy val salesLog = TableQuery[LogTable]

  // We also need a table for customers:
  final case class Customer(name: String, id: CustomerId = CustomerId(0L))

  final class CustomerTable(tag: Tag) extends Table[Customer](tag, "customers") {
    def id   = column[CustomerId]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    
    def * = (name, id).mapTo[Customer]
  }

  lazy val customers = TableQuery[CustomerTable]

  // We also need a table for staff:
  final case class Staff(name: String, id: StaffId = StaffId(0L))

  final class StaffTable(tag: Tag) extends Table[Staff](tag, "staff") {
    def id   = column[StaffId]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    
    def * = (name, id).mapTo[Staff]
  }

  lazy val staff = TableQuery[StaffTable]


  // We will want some items sold to insert.
  val sales = Seq(
    SaleEvent("abacus"       , Some("Alice") , None)          ,
    SaleEvent("bagpipes"     , None          , Some("Alice")) ,
    SaleEvent("catapult"     , None          , None)          ,
    SaleEvent("desk lamp"    , Some("Alice") , Some("Alice")) ,
    SaleEvent("elbow grease" , Some("Alice") , Some("Charlie"))  // <- NB, we have no Charlie in our staff
  )

  // How to record sales?
  // We take the sales and return the number of rows inserted for each event:
  def record(sales: Seq[SaleEvent]): DBIO[Seq[Int]] = {

    // We want to lookup a customers by nname
    // `max` is a trick to get us to a single value, rather than a Seq of values
    def customerQ(name: Option[String]): Rep[Option[CustomerId]] = name match {
      case Some(n) => customers.filter(_.name === n).map(_.id).max
      case None    => LiteralColumn(None)
    }

    def staffQ(name: Option[String]): Rep[Option[StaffId]] = name match {
      case Some(n) => staff.filter(_.name === n).map(_.id).max
      case None    => LiteralColumn(None)
    }

    // The values we want to insert consist of a tuple of a stirng, a query, and another query:
    def valuesQ(event: SaleEvent) = Query(
      ( LiteralColumn(event.productName), customerQ(event.customerName), staffQ(event.assistantName) )
    )

    // Turn each event into a query:
    val queries = sales.map(event => valuesQ(event))

    // Turn each query into an action.
    // Note that we need to map the salesLog into a tuple so the type of the insert matches the type of the query given to forceInsertQuery:
    val actions = queries.map(q => salesLog.map(row => (row.productName, row.customerId, row.assistantId)).forceInsertQuery(q))

    // Combine all the actions into one:
    DBIO.sequence(actions)
  }

  // Let's run this:

  val db = Database.forConfig("example")

  val program = for {
    _            <- (salesLog.schema ++ customers.schema ++ staff.schema).create
    aliceId      <- customers returning customers.map(_.id) += Customer("Alice")
    bobId        <- staff returning staff.map(_.id) += Staff("Bob")
    rowsInserted <- record(sales)
    result       <- salesLog.result
  } yield result


  println("Database after recording sales:")
  Await.result(db.run(program), 4.seconds).foreach(println)

  db.close()
}
