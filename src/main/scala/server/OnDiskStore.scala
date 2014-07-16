package server

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicLong, AtomicBoolean}

import com.codahale.metrics.Timer.Context
import com.google.common.collect.Sets
import com.google.common.io.Files
import common.{IdGenerator, Configuration, Logger}
import config.{ClusterEnv, Env}
import metrics.Metrics
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.collection.JavaConversions._
import scala.util.{Failure, Success, Try}

class OnDiskStore(val name: String, val config: Configuration, val path: File, val metricsenv: ClusterEnv, val metrics: Metrics, val clientOnly: Boolean) {

  private[this] val commitLog = new File(path, "commit.log")

  if (!path.exists()) path.mkdirs()
  if (!commitLog.exists()) commitLog.createNewFile()

  private[this] val options = new Options()
  private[this] val db = Iq80DBFactory.factory.open(new File(path, "leveldb"), options)

  private[this] val keySet = Sets.newConcurrentHashSet[String]()
  private[this] val memoryTable = new ConcurrentHashMap[String, JsValue]()
  private[this] val removedCache = Sets.newConcurrentHashSet[String]()
  private[this] val syncRunning = new AtomicBoolean(false)
  private[this] val checkSize = new AtomicLong(0L)

  private[this] val locks = new ConcurrentHashMap[String, Unit]()

  options.createIfMissing(true)

  restore()

  def commitFileTooBig(): Boolean = {
    if (checkSize.compareAndSet(50, 0)) {
      commitLog.length() > (16 * (1024 * 1024))
    } else {
      false
    }
  }

  def rotateLog(before: () => Unit = () => (), set: (String, String) => Unit, delete: (String) => Unit, after: () => Unit = () => (), fin: () => Unit = () => ()): Unit = {
    val back = new File(path, s"commit.snapshot-${IdGenerator.token(8)}")
    Files.move(commitLog, back)
    commitLog.createNewFile()
    before()
    try {
      Files.readLines(back, Env.UTF8).foreach { line =>
        line.split("\\:\\:\\:").toList match {
          case "SET" :: key :: json :: Nil => set(key, json)
          case "DELETE" :: key :: Nil => delete(key)
          case _ =>
        }
      }
      after()
      back.delete()
      back.deleteOnExit()
    } finally {
      fin()
    }
  }

  def restore() = {
    keySet.clear()
    memoryTable.clear()
    removedCache.clear()
    //val back = new File(path, s"commit.snapshot-${IdGenerator.token(8)}")
    //Files.move(commitLog, back)
    //commitLog.createNewFile()
    val batch = db.createWriteBatch()
    rotateLog(
      set = (key, json) => batch.put(Iq80DBFactory.bytes(key), Iq80DBFactory.bytes(json)),
      delete = key => batch.delete(Iq80DBFactory.bytes(key)),
      after = () => db.write(batch),
      fin = () => batch.close()
    )
    //try {
    //  Files.readLines(back, Env.UTF8).foreach { line =>
    //    line.split("\\:\\:\\:").toList match {
    //      case "SET" :: key :: json :: Nil => batch.put(Iq80DBFactory.bytes(key), Iq80DBFactory.bytes(json))
    //      case "DELETE" :: key :: Nil => batch.delete(Iq80DBFactory.bytes(key))
    //      case _ =>
    //    }
    //  }
    //  db.write(batch)
    //  back.delete()
    //  back.deleteOnExit()
    //} finally {
    //  batch.close()
    //}
    db.iterator().foreach { e =>
      keySet.add(Iq80DBFactory.asString(e.getKey))
    }
  }

  def locked(key: String) = {
    locks.containsKey(key)
  }

  def lock(key: String) = {
    locks.putIfAbsent(key, ())
  }

  def unlock(key: String) = {
    locks.remove(key)
  }

  def keys(): List[String] =  keySet.toList

  def close(): Unit = db.close()

  def destroy(): Unit = {
    Iq80DBFactory.factory.destroy(path, options)
    commitLog.delete()
    commitLog.deleteOnExit()
    path.delete()
    path.deleteOnExit()
  }

  private def awaitForUnlock(key: String, perform: => OpStatus, failStatus: OpStatus, ctx: Context, ctx2: Context): OpStatus = {
    if (locked(key)) {
      metrics.lock
      var attempts = 0
      while(locks.containsKey(key) || attempts > 50) {
        metrics.lockRetry
        attempts = attempts + 1
        Thread.sleep(1)
      }
    }
    if (locked(key)) {
      ctx.close()
      ctx2.close()
      failStatus
    } else perform
  }

  def forceSync() = syncCacheIfNecessary()

  private def syncCacheIfNecessary(): Unit = {
    if (!clientOnly) {
      if (commitFileTooBig()) {
        if (syncRunning.compareAndSet(false, true)) {
          val ctx = metrics.cacheSync
          Logger.trace(s"[$name] Sync cache with LevelDB ...")
          val batch = db.createWriteBatch()
          rotateLog(
            before =  () => {
              memoryTable.clear()
              removedCache.clear()
            },
            set = (key, json) => batch.put(Iq80DBFactory.bytes(key), Iq80DBFactory.bytes(json)),
            delete = key => batch.delete(Iq80DBFactory.bytes(key)),
            after = () => db.write(batch),
            fin = () => {
              batch.close()
              ctx.close()
              syncRunning.compareAndSet(true, false)
            }
          )
          //val back = new File(path, s"commit.copy-${IdGenerator.token(8)}")
          //Files.move(commitLog, back)
          //commitLog.createNewFile()
          //memoryTable.clear()
          //removedCache.clear()
          //try {
          //  Files.readLines(back, Env.UTF8).foreach { line =>
          //    line.split("\\:\\:\\:").toList match {
          //      case "SET" :: key :: json :: Nil => batch.put(Iq80DBFactory.bytes(key), Iq80DBFactory.bytes(json))
          //      case "DELETE" :: key :: Nil => batch.delete(Iq80DBFactory.bytes(key))
          //      case _ =>
          //    }
          //  }
          //  db.write(batch)
          //  back.delete()
          //  back.deleteOnExit()
          //} finally {
          //  batch.close()
          //  ctx.close()
          //  syncRunning.compareAndSet(true, false)
          //}
        }
      }
    }
  }

  private[this] def set(key: String, value: JsValue) = {
    keySet.add(key)
    if (removedCache.contains(key)) removedCache.remove(key)
    Option(memoryTable.put(key, value))
  }

  private[this] def get(key: String) = {
    if (memoryTable.containsKey(key)) {
      Some(memoryTable.get(key))
    } else if (removedCache.contains(key)) {
      None
    } else {
      Option(Iq80DBFactory.asString(db.get(Iq80DBFactory.bytes(key)))).map(Json.parse)
    }
  }

  private[this] def delete(key: String) = {
    keySet.remove(key)
    removedCache.add(key)
    Option(memoryTable.remove(key))
    //Try {
      //val opt1 = Option(memoryTable.remove(key))
      //val opt2 = Option(Iq80DBFactory.asString(db.get(Iq80DBFactory.bytes(key)))).map(Json.parse)
      //if (opt1.isDefined) opt1 else opt2
    //}.toOption.flatten
  }

  def setOperation(op: SetOperation, rollback: Boolean = false): OpStatus = {
    val ctx = metrics.startCommandIn
    val ctx2 = metrics.write
    def perform = {
      syncCacheIfNecessary()
      Files.append(s"SET:::${op.key}:::${Json.stringify(op.value)}\n", commitLog, Env.UTF8)
      val old = set(op.key, op.value)
      ctx.close()
      ctx2.close()
      OpStatus(true, op.key, None, op.timestamp, op.operationId, old)
    }
    val fail = OpStatus(false, op.key, None, op.timestamp, op.operationId)
    awaitForUnlock(op.key, perform, fail, ctx, ctx2)
  }

  def deleteOperation(op: DeleteOperation, rollback: Boolean = false): OpStatus = {
    val ctx = metrics.startCommandIn
    val ctx2 = metrics.delete
    def perform = {
      syncCacheIfNecessary()
      Files.append(s"DELETE:::${op.key}\n", commitLog, Env.UTF8)
      val old = delete(op.key)
      Try {
        db.delete(Iq80DBFactory.bytes(op.key))
        ctx.close()
        ctx2.close()
      } match {
        case Success(s) => OpStatus(true, op.key, None, op.timestamp, op.operationId, old)
        case Failure(e) => OpStatus(false, op.key, None, op.timestamp, op.operationId, old)
      }
    }
    val fail = OpStatus(false, op.key, None, op.timestamp, op.operationId)
    awaitForUnlock(op.key, perform, fail, ctx, ctx2)
  }

  def getOperation(op: GetOperation, rollback: Boolean = false): OpStatus = {
    val ctx = metrics.startCommandIn
    val ctx2 = metrics.read
    def perform = {
      syncCacheIfNecessary()
      val opt = get(op.key)
      ctx.close()
      ctx2.close()
      OpStatus(true, op.key, opt, op.timestamp, op.operationId)
    }
    val fail = OpStatus(false, op.key, None, op.timestamp, op.operationId)
    awaitForUnlock(op.key, perform, fail, ctx, ctx2)
  }

  def stats(): JsObject = {
    val keys: Int = this.keys().size
    val stats = Json.obj(
      "name" -> name,
      "keys" -> keys
    )
    stats
  }
}