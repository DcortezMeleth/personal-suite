package fin.api

import cats.effect.IO
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import fin.domain.*
import fin.repository.InvestmentRepository
import fin.service.{InvestmentService, InflationService}

object OwnerParam  extends OptionalQueryParamDecoderMatcher[String]("owner")
object MonthsParam extends OptionalQueryParamDecoderMatcher[Int]("months")

class InvestmentRoutes(
  investRepo:   InvestmentRepository,
  investService: InvestmentService,
  inflationSvc: InflationService
):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "portfolio" / "summary" :? OwnerParam(ownerStr) =>
      val owner = ownerStr.flatMap(s => scala.util.Try(AccountOwner.valueOf(s)).toOption)
      investService.portfolioSummary(owner).flatMap(s => Ok(s.asJson))

    case GET -> Root / "portfolio" / "positions" :? OwnerParam(ownerStr) =>
      val owner = ownerStr.flatMap(s => scala.util.Try(AccountOwner.valueOf(s)).toOption)
      investService.positions(owner).flatMap(list => Ok(list.asJson))

    case GET -> Root / "portfolio" / "inflation" :? MonthsParam(monthsOpt) =>
      inflationSvc.getInflationData(monthsOpt.getOrElse(36)).flatMap(list => Ok(list.asJson))

    case POST -> Root / "portfolio" / "inflation" / "fetch" =>
      inflationSvc.fetchAndStore.flatMap(n => Ok(s"""{"stored":$n}"""))

    // Treasury bonds
    case GET -> Root / "treasury-bonds" :? OwnerParam(ownerStr) =>
      val owner = ownerStr.flatMap(s => scala.util.Try(AccountOwner.valueOf(s)).toOption)
      investService.bondsWithValue(owner).flatMap(list => Ok(list.asJson))

    case req @ POST -> Root / "treasury-bonds" =>
      req.as[CreateTreasuryBond].flatMap { cmd =>
        investRepo.createBond(cmd).flatMap(b => Created(b.asJson))
      }

    // Deposits
    case GET -> Root / "deposits" :? OwnerParam(ownerStr) =>
      val owner = ownerStr.flatMap(s => scala.util.Try(AccountOwner.valueOf(s)).toOption)
      investService.depositsWithValue(owner).flatMap(list => Ok(list.asJson))

    case req @ POST -> Root / "deposits" =>
      req.as[CreateDeposit].flatMap { cmd =>
        investRepo.createDeposit(cmd).flatMap(d => Created(d.asJson))
      }

    // Investment transactions (manual entry)
    case req @ POST -> Root / "investment" / "transactions" =>
      req.as[CreateInvestmentTransaction].flatMap { cmd =>
        for
          instrument <- investRepo.findInstrumentBySymbol(cmd.instrumentSymbol.getOrElse("")).flatMap {
                          case Some(i) if cmd.instrumentSymbol.nonEmpty => IO.pure(i)
                          case _ =>
                            investRepo.createInstrument(
                              cmd.instrumentSymbol, cmd.instrumentName,
                              cmd.instrumentType, cmd.currency
                            )
                        }
          tx <- investRepo.insertInvestmentTx(
                  cmd.accountId, instrument.id, cmd.date, cmd.txType,
                  cmd.quantity, cmd.price, cmd.fees, cmd.amountPln, cmd.notes
                )
          res <- Created(tx.asJson)
        yield res
      }
  }
