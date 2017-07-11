import slick.jdbc.H2Profile.api._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

/*
  This example generates SQL that looks like:
 
 select "id" from "app_users" where "name" = 'Alice'
 select "id" from "app_users" where "name" = null
 insert into "sales_log" ("product_name","customer_id","assistant_id")  values (?,?,?)
 */
object Example2 extends App {

  // This set up is the same as the start of Example1


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
    SaleEvent("abacus"       , Some("Alice") , None)          ,
    SaleEvent("bagpipes"     , None          , Some("Alice")) ,
    SaleEvent("catapult"     , None          , None)          ,
    SaleEvent("desk lamp"    , Some("Alice") , Some("Alice")) ,
    SaleEvent("elbow grease" , Some("Alice") , Some("Bob"))  // <- NB, we have no user called Bob
  )

  //
  //The difference from Example1 is here
  //


  // How to record sales?
  // We take the sales and return the number of rows inserted for each event:
  def record(sales: Seq[SaleEvent]): DBIO[Seq[Int]] = {

    def userAction(name: Option[String]): DBIO[Option[UserId]] =
      users.filter(_.name === name).map(_.id).result.headOption

    def insert(event: SaleEvent): DBIO[Int] = for {
      customerId   <- userAction(event.customerName)
      assistantId  <- userAction(event.assistantName)
      rowsAffected <- salesLog += SalesLog(event.productName, customerId, assistantId)
    } yield rowsAffected
      
    DBIO.sequence(sales.map(insert))
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
