package io.github.arkida39.baku

import sttp.tapir.Endpoint

trait Resource {
  this: Contract =>
  final override type PublicEndpoint[INPUT, ERROR_OUTPUT, OUTPUT, -R] =
    Endpoint[Unit, INPUT, ERROR_OUTPUT, OUTPUT, R]
  final override type SecureEndpoint[
      SECURITY_INPUT,
      PRINCIPAL,
      INPUT,
      ERROR_OUTPUT,
      OUTPUT,
      -R
  ] = Endpoint[SECURITY_INPUT, INPUT, ERROR_OUTPUT, OUTPUT, R]
}
