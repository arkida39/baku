package io.github.arkida39.baku

private final case class FullSecureEndpoint[
    F[_],
    SECURITY_INPUT,
    PRINCIPAL,
    INPUT,
    ERROR_OUTPUT,
    OUTPUT
](
    val securityLogic: SECURITY_INPUT => F[
        Either[ERROR_OUTPUT, PRINCIPAL]
    ],
    val serverLogic: PRINCIPAL => INPUT => F[
        Either[ERROR_OUTPUT, OUTPUT]
    ]
) {}

private final case class PartialSecureEndpoint[
    F[_],
    SECURITY_INPUT,
    PRINCIPAL,
    ERROR_OUTPUT
](
    val securityLogic: SECURITY_INPUT => F[
        Either[ERROR_OUTPUT, PRINCIPAL]
    ]
) {

  def serverLogic[INPUT, OUTPUT, E2 >: ERROR_OUTPUT](
      f: PRINCIPAL => INPUT => F[Either[E2, OUTPUT]]
  ): FullSecureEndpoint[
      F,
      SECURITY_INPUT,
      PRINCIPAL,
      INPUT,
      E2,
      OUTPUT
  ] = FullSecureEndpoint(
      this.securityLogic
        .asInstanceOf[SECURITY_INPUT => F[Either[E2, PRINCIPAL]]],
      f
  )

}

trait Service[F[_]] {
  this: Contract =>
  final override type PublicEndpoint[INPUT, ERROR_OUTPUT, OUTPUT, -R] =
    INPUT => F[Either[ERROR_OUTPUT, OUTPUT]]

  protected final def securityLogic[
      SECURITY_INPUT,
      PRINCIPAL,
      ERROR_OUTPUT
  ](
      f: SECURITY_INPUT => F[Either[ERROR_OUTPUT, PRINCIPAL]]
  ): PartialSecureEndpoint[
      F,
      SECURITY_INPUT,
      PRINCIPAL,
      ERROR_OUTPUT
  ] = PartialSecureEndpoint(f)

  final override type SecureEndpoint[
      SECURITY_INPUT,
      PRINCIPAL,
      INPUT,
      ERROR_OUTPUT,
      OUTPUT,
      -R
  ] = FullSecureEndpoint[
      F,
      SECURITY_INPUT,
      PRINCIPAL,
      INPUT,
      ERROR_OUTPUT,
      OUTPUT
  ]
}
