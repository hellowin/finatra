package com.twitter.inject.server.tests

import com.google.inject.AbstractModule
import com.twitter.finagle.http.Status
import com.twitter.inject.app.App
import com.twitter.inject.server.{EmbeddedTwitterServer, Ports, TwitterServer}
import com.twitter.inject.{Test, TwitterModule}
import com.twitter.server.Lifecycle.Warmup
import com.twitter.server.{TwitterServer => BaseTwitterServer}
import com.twitter.util.Await
import com.twitter.util.registry.GlobalRegistry
import scala.util.parsing.json.JSON

class StartupIntegrationTest extends Test {

  override protected def afterEach(): Unit = {
    // "clear" GlobalRegistry
    GlobalRegistry.get.iterator foreach { entry =>
      GlobalRegistry.get.remove(entry.key)
    }
    super.afterEach()
  }

  test("ensure health check succeeds when guice config is good") {
    val server = new EmbeddedTwitterServer(new SimpleHttpTwitterServer)
    server.assertHealthy()

    server.httpGetAdmin("/admin/server_info", andExpect = Status.Ok)

    server.close()
  }

  test("non HTTP twitter-server passes health check") {
    val server = new EmbeddedTwitterServer(new SimpleTwitterServer)
    server.assertHealthy()
    server.close()
  }

  test("embedded raw com.twitter.server.Twitter starts up") {
    val server = new EmbeddedTwitterServer(twitterServer = new ExtendedBaseTwitterServer)

    server.assertHealthy()
    server.close()
  }

  test("TwitterServer starts up") {
    val server = new EmbeddedTwitterServer(twitterServer = new TwitterServer {})

    server.assertHealthy()
    server.close()
  }

  test("ensure server health check fails when guice config fails fast") {
    val server = new EmbeddedTwitterServer(new FailFastServer)
    intercept[Exception] {
      server.start()
    }
    server.close()
  }

  test("ensure startup fails when base twitter server preMain throws exception") {
    val server = new EmbeddedTwitterServer(new PremainErrorBaseTwitterServer)
    intercept[Exception] {
      server.start()
    }
    server.close()
  }

  test("ensure startup fails when preMain throws exception") {
    val server = new EmbeddedTwitterServer(new ServerPremainException)
    intercept[Exception] {
      server.start()
    }
    server.close()
  }

  test("ensure http server starts after warmup") {
    pending //only manually run since uses sleeps
    class WarmupServer extends TwitterServer {

      override def warmup(): Unit = {
        println("Warmup begin")
        Thread.sleep(1000)
        println("Warmup end")
      }
    }

    val server = new EmbeddedTwitterServer(twitterServer = new WarmupServer)

    server.assertHealthy(healthy = true)
    server.close()
  }

  test("calling install without a TwitterModule works") {
    val server = new EmbeddedTwitterServer(new ServerWithModuleInstall)
    server.start()
    server.close()
  }

  test("calling install with a TwitterModule throws exception") {
    val server = new EmbeddedTwitterServer(new ServerWithTwitterModuleInstall)
    intercept[Exception] {
      server.start()
    }
    server.close()
  }

  test("injector called before main") {
    val app = new App {
      override val modules = Seq(new TwitterModule {})
    }
    val e = intercept[Exception] {
      app.injector
    }
    app.close()
    e.getMessage should include("injector is not available before main")
  }

  test("register framework library") {
    val server = new EmbeddedTwitterServer(new ServerWithModuleInstall, disableTestLogging = true)
    try {
      server.start()

      val response = server.httpGetAdmin("/admin/registry.json", andExpect = Status.Ok)

      val json: Map[String, Any] =
        JSON.parseFull(response.contentString).get.asInstanceOf[Map[String, Any]]
      val registry = json("registry").asInstanceOf[Map[String, Any]]
      assert(registry.contains("library"))
      assert(registry("library").asInstanceOf[Map[String, String]].contains("finatra"))
    } finally {
      server.close()
    }
  }
}

class FailFastServer extends TwitterServer {
  override val modules = Seq(new AbstractModule {
    def configure() {
      throw new StartupTestException("guice module exception")
    }
  })
}

class SimpleTwitterServer extends TwitterServer {
  override val modules = Seq()
}

class SimpleHttpTwitterServer extends TwitterServer {}

class ServerWithTwitterModuleInstall extends TwitterServer {
  override val modules = Seq(new TwitterModule {
    override def configure() {
      install(new TwitterModule {})
    }
  })
}

class ServerWithModuleInstall extends TwitterServer {
  override val modules = Seq(new TwitterModule {
    override def configure() {
      install(new AbstractModule {
        override def configure(): Unit = {}
      })
    }
  })
}

class PremainErrorBaseTwitterServer extends BaseTwitterServer with Ports with Warmup {
  premain {
    throw new StartupTestException("premain exception")
  }

  def main() {
    warmupComplete()
    throw new StartupTestException("shouldn't get here")
  }
}

class ServerPremainException extends TwitterServer {
  premain {
    throw new StartupTestException("premain exception")
  }
}

class StartupTestException(msg: String) extends Exception(msg)

class ExtendedBaseTwitterServer extends BaseTwitterServer {
  def main() {
    Await.ready(adminHttpServer)
  }
}
