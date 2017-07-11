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
  case class UserId(value: Long) extends AnyVal with MappedTo[Long]

  final case class SalesLog(
    productName    : String,
    customer       : Option[UserId],
    salesAssistant : Option[UserId]
  )


  // Here's the table defintion for the records we want to store:
  final class LogTable(tag: Tag) extends Table[SalesLog](tag, "sales_log") {
    def productName = column[String]("product_name")
    def customerId  = column[Option[UserId]]("customer_id")
    def assistantId = column[Option[UserId]]("assistant_id")

    def * = (productName, customerId, assistantId).mapTo[SalesLog]
  }

  lazy val salesLog = TableQuery[LogTable]

  // We also need a table for users:
  final case class User(name: String, id: UserId = UserId(0L))

  final class UserTable(tag: Tag) extends Table[User](tag, "app_users") {
    def id = column[UserId]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    
    def * = (name, id).mapTo[User]
  }

  lazy val users = TableQuery[UserTable]


  // We will want some items sold to insert.
  // (Ok, Alice is both a member of staff and a shopper. I am lazy)
  val sales = Seq(
    SaleEvent("abacus"    , Some("Alice") , None)          ,
    SaleEvent("bagpipes"  , None          , Some("Alice")) ,
    SaleEvent("catapult"  , None          , None)          ,
    SaleEvent("desk lamp" , Some("Alice") , Some("Alice"))
  )

  // How to record sales?
  // We take the sales and return the number of rows inserted for each event:
  def record(sales: Seq[SaleEvent]): DBIO[Seq[Int]] = {

    // We want to lookup a user by name, and maybe get back a UserId:
    def userQ(name: Option[String]) = users.filter(_.name === name).map(_.id).max.asColumnOf[Option[UserId]]

    // The values we want to insert consist of a tuple of a stirng, a query, and another query:
    def valuesQ(event: SaleEvent) = Query(
      ( LiteralColumn(event.productName), userQ(event.customerName), userQ(event.assistantName) )
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
    _            <- (salesLog.schema ++ users.schema).create
    aliceId      <- users returning users.map(_.id) += User("Alice")
    rowsInserted <- record(sales)
    result       <- salesLog.result
  } yield result


  println("Database after recording sales:")
  Await.result(db.run(program), 4.seconds).foreach(println)

  db.close()
}
