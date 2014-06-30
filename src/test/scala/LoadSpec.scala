import java.util.concurrent.{Executors, TimeUnit}

import common.IdGenerator
import org.specs2.mutable.{Specification, Tags}
import play.api.libs.json.Json
import server._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

class LoadSpec extends Specification with Tags {
  sequential

  val timeout = Duration(10, TimeUnit.SECONDS)
  implicit val ec = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  def performBy(many: Int)(times: Int)(f: => Future[OpStatus]): Unit = {
    val start = System.currentTimeMillis()
    val future = Future.sequence(
      (0 to many).toList.map { _ =>
        Future {
          (0 to times).toList.map { _ =>
            Await.result(f, timeout)
          }
        }
      }
    )
    Await.result(future, Duration(10, TimeUnit.MINUTES))
    println(s"Injection in ${System.currentTimeMillis() - start} ms.")
  }

  "Distributed Map" should {

    val env = ClusterEnv(4)
    val node1 = KeyValNode(s"node1-${IdGenerator.token(6)}", env)
    val node2 = KeyValNode(s"node2-${IdGenerator.token(6)}", env)
    val node3 = KeyValNode(s"node3-${IdGenerator.token(6)}", env)
    val node4 = KeyValNode(s"node4-${IdGenerator.token(6)}", env)
    val node5 = KeyValNode(s"node5-${IdGenerator.token(6)}", env)
    val node6 = KeyValNode(s"node6-${IdGenerator.token(6)}", env)
    val node7 = KeyValNode(s"node7-${IdGenerator.token(6)}", env)
    val node8 = KeyValNode(s"node8-${IdGenerator.token(6)}", env)
    val node9 = KeyValNode(s"node9-${IdGenerator.token(6)}", env)
    val client = NodeClient(env)

    "Start some nodes" in {
      node1.start()
      node2.start()
      node3.start()
      node4.start()
      node5.start()
      node6.start()
      node7.start()
      node8.start()
      node9.start()
      client.start()
      Thread.sleep(6000)   // Wait for cluster setup
      success
    }

    "Insert some stuff" in {
      performBy(100)(1000) {
        val id = IdGenerator.uuid
        client.set(id, Json.obj(
          "Hello" -> "World", "key" -> id
        ))
      }
      success
    }

    "Stop the nodes" in {
      node1.displayStats().stop().destroy()
      node2.displayStats().stop().destroy()
      node3.displayStats().stop().destroy()
      node4.displayStats().stop().destroy()
      node5.displayStats().stop().destroy()
      node6.displayStats().stop().destroy()
      node7.displayStats().stop().destroy()
      node8.displayStats().stop().destroy()
      node9.displayStats().stop().destroy()
      client.stop()
      success
    }
  }
}