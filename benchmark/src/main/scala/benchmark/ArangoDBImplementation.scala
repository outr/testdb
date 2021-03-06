package benchmark

import cats.effect.IO
import com.arangodb.ArangoDB
import com.arangodb.entity.BaseDocument
import com.arangodb.mapping.ArangoJack
import com.arangodb.model.PersistentIndexOptions
import lightdb.util.FlushingBacklog

import java.util
import java.util.Collections
import scala.jdk.CollectionConverters._

object ArangoDBImplementation extends BenchmarkImplementation {
  override type TitleAka = BaseDocument
  override type TitleBasics = BaseDocument

  override def name: String = "ArangoDB"

  private lazy val db = new ArangoDB.Builder()
    .serializer(new ArangoJack())
    .password("root")
    .build()

  private lazy val titleAka = db.db("imdb").collection("titleAka")
  private lazy val titleBasics = db.db("imdb").collection("titleBasics")

  private lazy val backlogAka = new FlushingBacklog[BaseDocument](1000, 10000) {
    override protected def write(list: List[BaseDocument]): IO[Unit] = IO {
      titleAka.insertDocuments(list.asJavaCollection)
    }
  }

  private lazy val backlogBasics = new FlushingBacklog[BaseDocument](1000, 10000) {
    override protected def write(list: List[BaseDocument]): IO[Unit] = IO {
      titleBasics.insertDocuments(list.asJavaCollection)
    }
  }

  override def init(): IO[Unit] = IO {
    db.createDatabase("imdb")
    db.db("imdb").createCollection("titleAka")
    db.db("imdb").createCollection("titleBasics")
    db.db("imdb").collection("titleAka").ensurePersistentIndex(List("titleId").asJava, new PersistentIndexOptions)
  }

  override def map2TitleAka(map: Map[String, String]): BaseDocument = {
    val o = new BaseDocument()
    o.setKey(lightdb.Unique())
    o.addAttribute("titleId", map.value("titleId"))
    o.addAttribute("ordering", map.int("ordering"))
    o.addAttribute("title", map.value("title"))
    o.addAttribute("region", map.value("region"))
    o.addAttribute("language", map.value("language"))
    o.addAttribute("types", map.value("types"))
    o.addAttribute("attributes", map.value("attributes"))
    o.addAttribute("isOriginalTitle", map.value("isOriginalTitle"))
    o
  }

  override def map2TitleBasics(map: Map[String, String]): BaseDocument = {
    val o = new BaseDocument()
    o.setKey(lightdb.Unique())
    o.addAttribute("tconst", map.value("tconst"))
    o.addAttribute("titleType", map.value("titleType"))
    o.addAttribute("primaryTitle", map.value("primaryTitle"))
    o.addAttribute("originalTitle", map.value("originalTitle"))
    o.addAttribute("isAdult", map.value("isAdult"))
    o.addAttribute("startYear", map.value("startYear"))
    o.addAttribute("endYear", map.value("endYear"))
    o.addAttribute("runtimeMinutes", map.value("runtimeMinutes"))
    o.addAttribute("genres", map.value("genres"))
    o
  }

  override def persistTitleAka(t: BaseDocument): IO[Unit] = backlogAka.enqueue(t).map(_ => ())

  override def persistTitleBasics(t: BaseDocument): IO[Unit] = backlogBasics.enqueue(t).map(_ => ())

  override def flush(): IO[Unit] = for {
    _ <- backlogAka.flush()
    _ <- backlogBasics.flush()
  } yield {
    ()
  }

  override def idFor(t: BaseDocument): String = t.getKey

  override def titleIdFor(t: BaseDocument): String = t.getAttribute("titleId").asInstanceOf[String]

  override def streamTitleAka(): fs2.Stream[IO, BaseDocument] = {
    val cursor: java.util.Iterator[BaseDocument] = db.db("imdb").query("FOR t IN titleAka RETURN t", classOf[BaseDocument])
    val iterator = cursor.asScala
    fs2.Stream.fromBlockingIterator[IO](iterator, 512)
  }

  override def verifyTitleAka(): IO[Unit] = IO {
    val docs = titleAka.count().getCount
    scribe.info(s"TitleAka counts -- $docs")
  }

  override def verifyTitleBasics(): IO[Unit] = IO {
    val docs = titleBasics.count().getCount
    scribe.info(s"TitleBasics counts -- $docs")
  }

  override def get(id: String): IO[BaseDocument] = IO {
    titleAka.getDocument(id, classOf[BaseDocument])
  }

  override def findByTitleId(titleId: String): IO[List[BaseDocument]] = IO {
    val bindVars: java.util.Map[String, AnyRef] = Collections.singletonMap("titleId", titleId)
    val cursor: java.util.Iterator[BaseDocument] = db.db("imdb").query("FOR t IN titleAka FILTER t.titleId == @titleId RETURN t", bindVars, classOf[BaseDocument])
    cursor.asScala.toList
  }
}
