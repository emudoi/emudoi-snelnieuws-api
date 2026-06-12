package com.snelnieuws

import com.snelnieuws.db.Database
import org.scalatra.LifeCycle
import org.slf4j.LoggerFactory

import javax.servlet.ServletContext
import java.util.concurrent.atomic.AtomicReference

class ScalatraBootstrap extends LifeCycle {

  private val logger        = LoggerFactory.getLogger(classOf[ScalatraBootstrap])
  private val componentsRef = new AtomicReference[Option[Components]](None)

  override def init(context: ServletContext): Unit = {
    logger.info("Initializing snel-nieuws-api servlets...")

    try
      Database.migrate()
    catch {
      case e: Exception =>
        logger.error("Database migration failed — cannot start without tables", e)
        throw e
    }

    val components = Components.default()
    componentsRef.set(Some(components))

    context.mount(components.healthServlet, "/health/*")
    // Exact-path mounts win over the v1 catch-all `/*` regardless of
    // declaration order (Servlet API spec). Each of the routes below was
    // previously a Scalatra route inside NewsServlet; extracting them into
    // their own servlets is a no-op for callers — same URLs, same auth.
    // Two dispatch endpoints behind the same X-API-Key. The path is the
    // only thing that varies between them — production targets
    // api.push.apple.com (App Store + TestFlight tokens), sandbox targets
    // api.sandbox.push.apple.com (Xcode-debug install tokens).
    context.mount(components.notificationDispatchServlet, "/notifications/dispatch")
    context.mount(components.notificationDispatchSandboxServlet, "/notifications/dispatch-sandbox")
    // Broadcast endpoint — fans out a free-form text to either or both
    // environments based on feature_flags.is_enabled. Same X-API-Key auth.
    context.mount(components.notificationBroadcastServlet, "/notifications/broadcast")
    // Android dispatch + broadcast — fully parallel surface, X-API-Key
    // auth (same shared secret as the iOS endpoints). FCM has no
    // sandbox/production split so there is only one dispatch endpoint.
    context.mount(components.androidNotificationDispatchServlet, "/android/notifications/dispatch")
    context.mount(components.androidNotificationBroadcastServlet, "/android/notifications/broadcast")
    context.mount(components.staticContentServlet, "/privacy")
    context.mount(components.staticContentServlet, "/support")
    context.mount(components.staticContentServlet, "/account-deletion")
    // /v2/images/* is mounted as its own open servlet (no X-Client /
    // X-Client-Key gate) so iOS's plain AsyncImage(url: ...) works
    // header-free. The Servlet API picks the longest matching prefix,
    // so this wins over /v2/* below regardless of declaration order.
    context.mount(components.imageServlet, "/v2/images/*")
    // Android-specific v2 surface (subscribe + delete + register). Mounted
    // BEFORE /v2/* in spirit — the Servlet API resolves by longest prefix
    // anyway, so /v2/android/* always wins over /v2/*. Independent
    // before-filter that requires `X-Client: android/<v>`.
    context.mount(components.androidNotificationsServletV2, "/v2/android/*")
    // Video reel: /v3/videos/feed (gated) + /v3/videos/:id/stream (open MP4
    // proxy). Longest-prefix wins over /v3/* regardless of order.
    context.mount(components.videosServletV3, "/v3/videos/*")
    context.mount(components.newsServletV3, "/v3/*")
    context.mount(components.newsServletV2, "/v2/*")
    // v1 catch-all stays mounted last, conceptually — kept here for
    // readability of the routing table.
    context.mount(components.newsServlet, "/*")

    components.startBackgroundWorkers()

    logger.info("snel-nieuws-api servlets initialized successfully")
  }

  override def destroy(context: ServletContext): Unit = {
    componentsRef.get().foreach(_.close())
    super.destroy(context)
  }
}
