import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{Executors, TimeUnit}

import common.{Logger, IdGenerator}
import org.specs2.mutable.{Specification, Tags}
import play.api.libs.json.Json
import server.DistributedMapNode

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration

class NodeDownSpec extends Specification with Tags {
  sequential

  "Distributed Map" should {

    implicit val timeout = Duration(10, TimeUnit.SECONDS)
    implicit val ec = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

    val node1 = DistributedMapNode(s"node1-${IdGenerator.uuid}", 4)
    val node2 = DistributedMapNode(s"node2-${IdGenerator.uuid}", 4)
    val node3 = DistributedMapNode(s"node3-${IdGenerator.uuid}", 4)
    val node4 = DistributedMapNode(s"node4-${IdGenerator.uuid}", 4)
    val node5 = DistributedMapNode(s"node5-${IdGenerator.uuid}", 4)
    val node6 = DistributedMapNode(s"node6-${IdGenerator.uuid}", 4)
    val node7 = DistributedMapNode(s"node7-${IdGenerator.uuid}", 4)
    val node8 = DistributedMapNode(s"node8-${IdGenerator.uuid}", 4)
    val node9 = DistributedMapNode(s"node9-${IdGenerator.uuid}", 4)
    var keys = Seq[String]()
    val counterOk = new AtomicLong(0L)
    val counterKo = new AtomicLong(0L)

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
      Thread.sleep(6000)   // Wait for cluster setup
      success
    }

    "Insert some stuff" in {
      for (i <- 0 to 1000) {
        val id = IdGenerator.uuid
        keys = keys :+ id
        Await.result( node1.set(id, Json.obj(
          "Hello" -> "World", "key" -> id
        )), timeout)
      }
      success
    }

    "Shutdown some nodes" in {
      node2.displayStats().stop().destroy()
      node4.displayStats().stop().destroy()
      node6.displayStats().stop().destroy()
      Thread.sleep(20000)
      success
    }

    "Read some stuff" in {
      keys.foreach { key =>
        val expected = Some(Json.obj("Hello" -> "World", "key" -> key))
        if (Await.result(node1.get(key), timeout) == expected) counterOk.incrementAndGet()
        else counterKo.incrementAndGet()
        //shouldEqual Some(Json.obj("Hello" -> "World", "key" -> key))
      }
      success
    }

    "Delete stuff" in {
      keys.foreach { key =>
        Await.result(node1.delete(key), timeout)
      }
      success
    }

    "Stop the nodes" in {
      node1.displayStats().stop().destroy()
      node3.displayStats().stop().destroy()
      node5.displayStats().stop().destroy()
      node7.displayStats().stop().destroy()
      node8.displayStats().stop().destroy()
      node9.displayStats().stop().destroy()
      Thread.sleep(5000)
      Logger.info(s"Read OK ${counterOk.get()}")
      Logger.info(s"Read KO ${counterKo.get()}")
      counterKo.get() shouldEqual 0L
      success
    }
  }
}