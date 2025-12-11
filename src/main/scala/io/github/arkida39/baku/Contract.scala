package io.github.arkida39.baku

trait Contract {
  type PublicEndpoint[INPUT, ERROR_OUTPUT, OUTPUT, -R]
  type SecureEndpoint[
      SECURITY_INPUT,
      PRINCIPAL,
      INPUT,
      ERROR_OUTPUT,
      OUTPUT,
      -R
  ]
}
