package com.snelnieuws.service

import com.snelnieuws.repository.ArticleRepository
import com.snelnieuws.model.{Article, ArticleCreate, ArticleRow}

import scala.collection.mutable
import scala.util.Random

class ArticleService(repository: ArticleRepository) {

  import ArticleService._

  def findAll(limit: Int = 100): Either[Throwable, List[Article]] =
    repository.findAll(limit).map(_.map(toArticle)).map(interleaveBySource)

  def findByCategory(category: String, limit: Int = 100): Either[Throwable, List[Article]] =
    repository.findByCategory(category, limit).map(_.map(toArticle)).map(interleaveBySource)

  def search(query: String, limit: Int = 100): Either[Throwable, List[Article]] =
    repository.search(query, limit).map(_.map(toArticle)).map(interleaveBySource)

  def create(article: ArticleCreate): Either[Throwable, Article] =
    repository.create(article).map(toArticle)

  def findById(id: Long): Either[Throwable, Option[Article]] =
    repository.findById(id).map(_.map(toArticle))

  def delete(id: Long): Either[Throwable, Int] =
    repository.delete(id)

  def findCategories(): Either[Throwable, List[String]] =
    repository.findDistinctCategories()

  // /everything route logic — empty / "news" → all; otherwise try category, fall back to search.
  def findEverything(query: String, limit: Int = 100): Either[Throwable, List[Article]] =
    if (query.isEmpty || query == "news") {
      findAll(limit)
    } else {
      findByCategory(query, limit).flatMap { byCategory =>
        if (byCategory.nonEmpty) Right(byCategory)
        else search(query, limit)
      }
    }

  // /top-headlines route logic — empty category → all; otherwise filter by category.
  def findTopHeadlines(category: String, limit: Int = 100): Either[Throwable, List[Article]] =
    if (category.isEmpty) findAll(limit)
    else findByCategory(category, limit)
}

object ArticleService {

  private def toArticle(row: ArticleRow): Article = Article(
    id = row.id.toString,
    author = row.author,
    title = row.title,
    description = row.description,
    url = row.url,
    urlToImage = row.urlToImage,
    publishedAt = row.publishedAt,
    content = row.content,
    category = row.category
  )

  // Shuffle articles and reorder so that two articles from the same source
  // (author) are never adjacent when avoidable. Articles with no author each
  // act as their own unique source so they may sit next to each other freely.
  private def interleaveBySource(articles: List[Article]): List[Article] = {
    if (articles.length <= 1) return articles

    val buffers = mutable.Map[String, mutable.Queue[Article]]()
    var anonCounter = 0
    articles.foreach { a =>
      val key = a.author match {
        case Some(s) if s.nonEmpty => s
        case _ =>
          anonCounter += 1
          s"__anon__$anonCounter"
      }
      buffers.getOrElseUpdate(key, mutable.Queue.empty).enqueue(a)
    }
    buffers.keys.foreach { k =>
      val shuffled = Random.shuffle(buffers(k).toList)
      buffers(k) = mutable.Queue(shuffled: _*)
    }

    val result = mutable.ListBuffer[Article]()
    var lastSource: Option[String] = None
    while (buffers.nonEmpty) {
      val ranked = Random.shuffle(buffers.toList).sortBy(-_._2.size)
      val pick = ranked.find { case (k, _) => !lastSource.contains(k) }
        .orElse(ranked.headOption)
      pick.foreach { case (k, q) =>
        result += q.dequeue()
        lastSource = Some(k)
        if (q.isEmpty) buffers.remove(k)
      }
    }
    result.toList
  }
}
