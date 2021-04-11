package lightdb.index.lucene

import cats.effect.IO
import com.outr.lucene4s._
import com.outr.lucene4s.field.value.FieldAndValue
import com.outr.lucene4s.field.{Field => LuceneField}
import com.outr.lucene4s.query.SearchResult
import lightdb.collection.Collection
import lightdb.field.Field
import lightdb.index.Indexer
import lightdb.query.{PagedResults, Query, ResultDoc}
import lightdb.{Document, Id}

case class LuceneIndexer[D <: Document[D]](collection: Collection[D], autoCommit: Boolean = false) extends Indexer[D] {
  private val lucene = new DirectLucene(
    uniqueFields = List("_id"),
    defaultFullTextSearchable = true,
    autoCommit = autoCommit
  )
  private[lucene] val _fields: List[IndexedField[Any]] = collection.mapping.fields.flatMap { field =>
    val indexFeatureOption = field.features.collectFirst {
      case indexFeature: IndexFeature[_] => indexFeature.asInstanceOf[IndexFeature[Any]]
    }
    indexFeatureOption.map(indexFeature => IndexedField[Any](indexFeature.createField(field.name, lucene), field.asInstanceOf[Field[D, Any]]))
  }

  private[lucene] val fields: List[IndexedField[Any]] = _fields match {
    case list if list.exists(_.field.name == "_id") => list
    case list => id.asInstanceOf[IndexedField[Any]] :: list
  }

  lazy val id: IndexedField[Id[D]] = _fields
    .find(_.field.name == "_id")
    .map(_.asInstanceOf[IndexedField[Id[D]]])
    .getOrElse(IndexedField(lucene.create.field[Id[D]]("_id"), Field("_id", _._id, Nil)))

  override def put(value: D): IO[D] = if (fields.nonEmpty) {
    IO {
      val fieldsAndValues = fields.map(_.fieldAndValue(value))
      lucene.doc().fields(fieldsAndValues: _*).index()
      value
    }
  } else {
    IO.pure(value)
  }

  override def delete(id: Id[D]): IO[Unit] = IO(lucene.delete(parse(s"_id:${id.value}")))

  override def commit(): IO[Unit] = IO {
    lucene.commit()
  }

  override def count(): IO[Long] = IO {
    lucene.count()
  }

  override def search(query: Query[D]): IO[PagedResults[D]] = IO {
    LucenePagedResults(this, query, lucene.query().search())
  }

  override def dispose(): IO[Unit] = IO(lucene.dispose())

  case class IndexedField[F](luceneField: LuceneField[F], field: Field[D, F]) {
    def fieldAndValue(value: D): FieldAndValue[F] = luceneField(field.getter(value))
  }
}

case class LucenePagedResults[D <: Document[D]](indexer: LuceneIndexer[D],
                                                query: Query[D],
                                                lpr: com.outr.lucene4s.query.PagedResults[SearchResult]) extends PagedResults[D] {
  override def total: Long = lpr.total

  override def documents: List[ResultDoc[D]] = lpr.entries.toList.map(r => LuceneResultDoc(this, r))
}

case class LuceneResultDoc[D <: Document[D]](results: LucenePagedResults[D], result: SearchResult) extends ResultDoc[D] {
  override lazy val id: Id[D] = result(results.indexer.id.luceneField)
  override def get(): IO[D] = results.query.collection(id)

  override def apply[F](field: Field[D, F]): F = {
    val indexedField = results.indexer.fields.find(_.field.name == field.name).getOrElse(throw new RuntimeException(s"Unable to find indexed field for: ${field.name}"))
    result(indexedField.luceneField).asInstanceOf[F]
  }
}