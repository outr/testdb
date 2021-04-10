package lightdb.index

import cats.effect.IO
import lightdb.collection.Collection
import lightdb.query.Query
import lightdb.{Document, Id, ObjectMapping}

trait Indexer[D <: Document[D]] {
  protected def collection: Collection[D]

  def put(value: D): IO[D]

  def delete(id: Id[D]): IO[Unit]

  def commit(): IO[Unit]

  def count(): IO[Long]

  def search(query: Query[D]): IO[PagedResults[D]]

  def dispose(): IO[Unit]
}