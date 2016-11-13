package org.vertx.demo;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.*;

public class MainVerticle extends AbstractVerticle {

	@Override
	public void start() {
		vertx.createHttpServer()
				.requestHandler(req -> req.response().end("Hello World!"))
				.listen(8080);
	}
}