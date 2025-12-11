package io.github.arkida39.baku

import sttp.tapir.server.ServerEndpoint
import scala.annotation.experimental
import scala.collection.mutable.Buffer

sealed trait Component[-CR, F[_]]() {
  this: Contract =>
  final override type PublicEndpoint[INPUT, ERROR_OUTPUT, OUTPUT, -R] =
    ServerEndpoint.Full[Unit, Unit, INPUT, ERROR_OUTPUT, OUTPUT, R, F]
  final override type SecureEndpoint[
      SECURITY_INPUT,
      PRINCIPAL,
      INPUT,
      ERROR_OUTPUT,
      OUTPUT,
      -R
  ] = ServerEndpoint.Full[
      SECURITY_INPUT,
      PRINCIPAL,
      INPUT,
      ERROR_OUTPUT,
      OUTPUT,
      R,
      F
  ]
  lazy val all: List[ServerEndpoint[CR, F]]
}

@experimental
object Component {
  transparent inline def of[C <: Contract, F[_]](
      resource: Resource & C,
      service: Service[F] & C
  ): Any = ${ makeComponent[C, F, resource.type, service.type]('resource, 'service) }

  import scala.quoted.*
  def makeComponent[
      C <: Contract: Type,
      F[_]: Type,
      R <: Resource & C: Type,
      S <: Service[?]: Type
  ](
      resource: Expr[Resource & C],
      service: Expr[Service[F] & C]
  )(using Quotes): Expr[Any] = {
    import quotes.reflect.*
    import sttp.tapir.*

    val contractType = TypeRepr.of[C]
    val resourceType = TypeRepr.of[R]
    val serviceType = TypeRepr.of[S]
    val fType = TypeRepr.of[F]

    val publicEndpointSym = contractType.typeSymbol.typeMember("PublicEndpoint")
    val secureEndpointSym = contractType.typeSymbol.typeMember("SecureEndpoint")

    type PublicEndpointMapper[T] =
      [INPUT, ERROR_OUTPUT, OUTPUT, R] => Unit => Type[
          INPUT
      ] ?=> Type[ERROR_OUTPUT] ?=> Type[OUTPUT] ?=> Type[R] ?=> T

    type SecureEndpointMapper[T] = [
        SECURITY_INPUT,
        PRINCIPAL,
        INPUT,
        ERROR_OUTPUT,
        OUTPUT,
        R
    ] => Unit => Type[SECURITY_INPUT] ?=> Type[PRINCIPAL] ?=> Type[
        INPUT
    ] ?=> Type[
        ERROR_OUTPUT
    ] ?=> Type[OUTPUT] ?=> Type[R] ?=> T

    def mapEndpointAliases[T](memberType: TypeRepr)(
        onPublicEndpoint: PublicEndpointMapper[T],
        onSecureEndpoint: SecureEndpointMapper[T]
    )(using Quotes): Option[T] = {

      memberType.typeSymbol match {
        case `publicEndpointSym` =>
          memberType.typeArgs match {
            case List(i, e, o, r) =>
              (i.asType, e.asType, o.asType, r.asType) match {
                case ('[i], '[e], '[o], '[r]) =>
                  Some(
                      onPublicEndpoint[i, e, o, r](())
                  )
                case _ => None
              }
            case _ => None
          }
        case `secureEndpointSym` =>
          memberType.typeArgs match {
            case List(sec, p, i, e, o, r) =>
              (
                  sec.asType,
                  p.asType,
                  i.asType,
                  e.asType,
                  o.asType,
                  r.asType
              ) match {
                case (
                        '[sec],
                        '[p],
                        '[i],
                        '[e],
                        '[o],
                        '[r]
                    ) =>
                  Some(
                      onSecureEndpoint[sec, p, i, e, o, r](())
                  )
                case _ => None
              }
            case _ => None
          }
        case _ => {
          report.errorAndAbort(
              "FATAL: Something went wrong. 'mapEndpointAliases' received a non-endpoint member."
          )
          None
        }
      }
    }

    val serviceSym = TypeRepr.of[Service].typeSymbol

    val contractVals = contractType.typeSymbol.declarations.filter { sym =>
      sym.isValDef &&
      sym.flags.is(Flags.Deferred) && // Abstract
      !sym.flags.is(Flags.Synthetic) &&
      !sym.flags.is(Flags.Artifact)
    }

    if (
        (contractType.typeSymbol.fieldMembers ++ contractType.typeSymbol.typeMembers ++ contractType.typeSymbol.methodMembers)
          .exists(sym =>
            sym.flags.is(Flags.Deferred) && // Abstract
              !sym.flags.is(Flags.Synthetic) &&
              !sym.flags.is(
                  Flags.Artifact
              ) && !(sym == publicEndpointSym || sym == secureEndpointSym || contractVals
                .contains(sym))
          )
    ) then {
      report.errorAndAbort(
          "Contract traits must not contain other abstract members except endpoint definitions."
      )
    }

    // Used to calculate the final/combined capability requirements of all endpoints.
    var finalCapabilities = TypeRepr.of[Any]

    val wirings = contractVals.view.flatMap { sym =>
      val name = sym.name
      val memberType = contractType.memberType(sym)
      val resourceMemberType =
        resourceType.memberType(resourceType.typeSymbol.fieldMember(name))
      val serviceMemberType =
        serviceType.memberType(serviceType.typeSymbol.fieldMember(name))

      val resourceSelect = Select(
          resource.asTerm,
          resourceType.typeSymbol.fieldMember(name)
      )
      val serviceSelect = Select(
          service.asTerm,
          serviceType.typeSymbol.fieldMember(name)
      )

      val expr = mapEndpointAliases[Expr[Any]](memberType)(
          onPublicEndpoint = (
              [I, E, O, R] =>
                _ => {
                  finalCapabilities = AndType(finalCapabilities, TypeRepr.of[R])
                  '{
                    ${ resourceSelect.asExprOf[Endpoint[Unit, I, E, O, R]] }
                      .serverLogic[F](${
                        serviceSelect.asExprOf[I => F[Either[E, O]]]
                      })
                  }
                }
          ),
          onSecureEndpoint = (
              [SEC, P, I, E, O, R] =>
                _ => {
                  finalCapabilities = AndType(finalCapabilities, TypeRepr.of[R])
                  '{
                    ${ resourceSelect.asExprOf[Endpoint[SEC, I, E, O, R]] }
                      .serverSecurityLogic[P, F](${
                        serviceSelect
                          .asExprOf[FullSecureEndpoint[F, SEC, P, I, E, O]]
                      }.securityLogic)
                      .serverLogic(${
                        serviceSelect
                          .asExprOf[FullSecureEndpoint[F, SEC, P, I, E, O]]
                      }.serverLogic)
                  }
                }
          )
      )

      if expr.isDefined then Seq(name -> expr)
      else None
    }.toMap

    contractType.asType match {
      case '[c] =>
        finalCapabilities.simplified.asType match {
          case '[r] => {
            val parents = List(
                TypeTree.of[Object],
                TypeTree.of[C],
                TypeTree.of[Component[r, F]]
            )
            val resultType = TypeTree.of[C & Component[r, F]]

            val valDefs = Buffer[ValDef]()
            val cls = Symbol.newClass(
                Symbol.spliceOwner,
                Symbol.freshName("BakuComponent$Generated"),
                parents.map(_.tpe),
                decls = clsSym => {
                  // Contract members
                  val contractValsSyms = contractVals.map { member =>
                    val valType = mapEndpointAliases[TypeRepr](
                        contractType.memberType(member)
                    )(
                        onPublicEndpoint = (
                            [I, E, O, R] =>
                              _ =>
                                (
                                    TypeRepr.of[
                                        ServerEndpoint.Full[Unit, Unit, I, E, O, R, F]
                                    ]
                                )
                        ),
                        onSecureEndpoint = (
                            [SEC, P, I, E, O, R] =>
                              _ =>
                                (
                                    TypeRepr
                                      .of[ServerEndpoint.Full[SEC, P, I, E, O, R, F]]
                                )
                        )
                    )

                    val valSym =
                      Symbol.newVal(
                          clsSym,
                          member.name,
                          valType.get,
                          Flags.Override,
                          Symbol.noSymbol
                      )
                    valDefs.append(
                        ValDef(
                            valSym,
                            Some(
                                wirings
                                  .get(member.name)
                                  .get
                                  .get
                                  .asTerm
                                  .changeOwner(valSym)
                            )
                        )
                    )
                    valSym
                  }

                  val allSym = Symbol.newVal(
                      clsSym,
                      "all",
                      TypeRepr.of[List[ServerEndpoint[r, F]]],
                      Flags.Override | Flags.Lazy,
                      Symbol.noSymbol
                  )
                  valDefs.append(
                      ValDef(
                          allSym,
                          Some(
                              Expr
                                .ofList(
                                    contractValsSyms.map(sym =>
                                      Ref(sym).asExprOf[ServerEndpoint[r, F]]
                                    )
                                )
                                .asTerm
                                .changeOwner(allSym)
                          )
                      )
                  )

                  allSym :: contractValsSyms
                },
                selfType = Some(resultType.tpe)
            )

            val clsDef = ClassDef(
                cls,
                parents,
                body = valDefs.toList
            )

            Block(
                List(clsDef),
                Typed(
                    Apply(Select(New(TypeIdent(cls)), cls.primaryConstructor), Nil),
                    resultType
                )
            ).asExpr
          }
        }
    }
  }

}
