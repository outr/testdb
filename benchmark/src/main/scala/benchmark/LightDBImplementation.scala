package benchmark

import cats.effect.IO
import fabric.rw.{ReaderWriter, ccRW}
import lightdb.{Document, Id, JsonMapping, LightDB}
import lightdb.collection.Collection
import lightdb.index.lucene.LuceneIndexerSupport
import lightdb.store.halo.SharedHaloSupport
import lightdb.index.lucene._

import java.nio.file.Paths

object LightDBImplementation extends BenchmarkImplementation {
  override type TitleAka = TitleAkaLDB
  override type TitleBasics = TitleBasicsLDB

  override def name: String = "LightDB"

  override def map2TitleAka(map: Map[String, String]): TitleAkaLDB = TitleAkaLDB(
    titleId = map.value("titleId"),
    ordering = map.int("ordering"),
    title = map.value("title"),
    region = map.option("region"),
    language = map.option("language"),
    types = map.list("types"),
    attributes = map.list("attributes"),
    isOriginalTitle = map.boolOption("isOriginalTitle")
  )


  override def map2TitleBasics(map: Map[String, String]): TitleBasicsLDB = TitleBasicsLDB(
    tconst = map.value("tconst"),
    titleType = map.value("titleType"),
    primaryTitle = map.value("primaryTitle"),
    originalTitle = map.value("originalTitle"),
    isAdult = map.bool("isAdult"),
    startYear = map.int("startYear"),
    endYear = map.int("endYear"),
    runtimeMinutes = map.int("runtimeMinutes"),
    genres = map.list("genres"),
  )

  override def persistTitleAka(t: TitleAkaLDB): IO[Unit] = db.titleAka.put(t).map(_ => ())

  override def persistTitleBasics(t: TitleBasicsLDB): IO[Unit] = db.titleBasics.put(t).map(_ => ())

  override def streamTitleAka(): fs2.Stream[IO, TitleAkaLDB] = db.titleAka.all()

  override def idFor(t: TitleAkaLDB): String = t._id.value

  override def titleIdFor(t: TitleAkaLDB): String = t.titleId

  override def get(id: String): IO[TitleAkaLDB] = db.titleAka.get(Id[TitleAkaLDB](id)).map(_.getOrElse(throw new RuntimeException(s"$id not found")))

  override def flush(): IO[Unit] = db.titleAka.commit()

  override def verifyTitleAka(): IO[Unit] = for {
    haloCount <- db.titleAka.store.count()
    luceneCount <- db.titleAka.indexer.count()
  } yield {
    scribe.info(s"TitleAka counts -- Halo: $haloCount, Lucene: $luceneCount")
    ()
  }

  object db extends LightDB(directory = Some(Paths.get("imdb"))) with LuceneIndexerSupport with SharedHaloSupport {
    override protected def haloIndexThreads: Int = 10
    override protected def haloMaxFileSize: Int = 1024 * 1024 * 10    // 10 meg

    val titleAka: Collection[TitleAkaLDB] = collection("titleAka", TitleAkaLDB)
    val titleBasics: Collection[TitleBasicsLDB] = collection("titleBasics", TitleBasicsLDB)
  }

  case class TitleAkaLDB(titleId: String, ordering: Int, title: String, region: Option[String], language: Option[String], types: List[String], attributes: List[String], isOriginalTitle: Option[Boolean], _id: Id[TitleAka] = Id[TitleAka]()) extends Document[TitleAka]

  object TitleAkaLDB extends JsonMapping[TitleAkaLDB] {
    override implicit val rw: ReaderWriter[TitleAkaLDB] = ccRW

    val titleId: FD[String] = field("titleId", _.titleId).indexed()
    val ordering: FD[Int] = field("ordering", _.ordering).indexed()
    val title: FD[String] = field("title", _.title).indexed()
  }

  case class TitleBasicsLDB(tconst: String, titleType: String, primaryTitle: String, originalTitle: String, isAdult: Boolean, startYear: Int, endYear: Int, runtimeMinutes: Int, genres: List[String], _id: Id[TitleBasics] = Id[TitleBasics]()) extends Document[TitleBasics]

  object TitleBasicsLDB extends JsonMapping[TitleBasicsLDB] {
    override implicit val rw: ReaderWriter[TitleBasicsLDB] = ccRW

    val tconst: FD[String] = field("tconst", _.tconst).indexed()
    val primaryTitle: FD[String] = field("primaryTitle", _.primaryTitle).indexed()
    val originalTitle: FD[String] = field("originalTitle", _.originalTitle).indexed()
  }
}
