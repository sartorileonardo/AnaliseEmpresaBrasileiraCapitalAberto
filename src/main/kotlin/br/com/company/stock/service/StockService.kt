package br.com.company.stock.service

import br.com.company.stock.client.dto.ResponseDTO
import br.com.company.stock.config.StockParametersConfig
import br.com.company.stock.dto.Score
import br.com.company.stock.dto.StockAnalisysDTO
import br.com.company.stock.mapper.StockMapper
import br.com.company.stock.repository.StockRepository
import br.com.company.stock.utils.ValueUtils
import br.com.company.stock.validation.TickerValidation
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.util.StringUtils
import reactor.core.publisher.Mono

@Service
class StockService(
    val configuration: StockParametersConfig,
    val mapper: StockMapper,
    val repository: StockRepository
) {
    companion object {
        var LOGGER: Logger? = LoggerFactory.getLogger(StockService::class.java)
    }

    @Cacheable(value = ["analisys"])
    fun getAnalisys(ticker: String): Mono<StockAnalisysDTO> {
        TickerValidation.validateTicker(ticker)

        if (repository.existsById(ticker).block()!!) {
            return getAnalisysWhenAlreadyExist(ticker)
        }

        return getAnalisysWhenDoesNotYetExist(ticker)
    }

    private fun getAnalisysWhenDoesNotYetExist(ticker: String): Mono<StockAnalisysDTO> {
        val entity = mapper.toEntity(getExternalAnalisys(ticker))

        return repository.save(entity)
            .map {
                it.score = getScoreEvaluation(it.toString())
                mapper.toDTO(it)
            }
            .doOnSuccess { LOGGER?.info("Analysis from external API performed and save successfully: $ticker") }
            .doOnError { LOGGER?.error("Analysis from external API did find an error and don't save $ticker: \nCause: ${it.message} \nMessage: ${it.message}") }
    }

    private fun getAnalisysWhenAlreadyExist(ticker: String): Mono<StockAnalisysDTO> {
        return repository.findById(ticker)
            .map {
                it.score = getScoreEvaluation(it.toString())
                mapper.toDTO(it)
            }
            .doOnSuccess { LOGGER?.info("Analysis from database performed successfully: $ticker") }
            .doOnError { LOGGER?.error("Analysis from database did find an error $ticker: \nCause: ${it.message} \nMessage: ${it.message}") }
    }

    private fun getScoreEvaluation(analisyToString: String): Score {
        val positivePoints = StringUtils.countOccurrencesOf(analisyToString, "true")
        val excellentRange = 8..10
        val goodRange = 6..7
        val fairRange = 3..5
        val badRange = 0..2

        when (positivePoints) {
            in badRange -> return Score.BAD
            in fairRange -> return Score.FAIR
            in goodRange -> return Score.GOOD
            in excellentRange -> return Score.EXCELLENT
            else -> return Score.UNDEFINITE
        }
    }

    fun getExternalAnalisys(ticker: String): StockAnalisysDTO {
        val responseDTO =
            ResponseDTO.parseMapToDto(
                br.com.company.stock.client.StockWebClient(configuration).getContentFromAPI(ticker)
            )
        val indicatorsTicker = responseDTO.indicatorsTicker
        val valuation = responseDTO.valuation
        val paper = responseDTO.paper
        val alternativesIndicators = paper?.indicadores
        val alternativeIndicatorPVP =
            if (alternativesIndicators?.get(1)?.Value_F == null) ValueUtils.getDoubleValue(valuation?.pvp.toString()) else ValueUtils.getDoubleValue(
                alternativesIndicators.get(1).Value_F!!
            )
        val priceOnBookValue = ValueUtils.getDoubleValue(
            listOfNotNull(indicatorsTicker?.pvp, alternativeIndicatorPVP, valuation?.pvp).distinct().first().toString()
        )
        val company = responseDTO.company
        val netMargin = ValueUtils.getDoubleValue(company?.margemLiquida.toString())
        val returnOnEquity = ValueUtils.getDoubleValue(company?.roe.toString())
        val cagrFiveYears = ValueUtils.getDoubleValue(company?.lucros_Cagr5.toString())
        val sectorOfActivity = company?.setor_Atuacao_clean.toString()
        val companyIsInJudicialRecovery = company?.injudicialProcess.toString().toBoolean()
        val currentLiquidity = ValueUtils.getDoubleValue(company?.liquidezCorrente.toString())
        val netDebitOverNetEquity = ValueUtils.getDoubleValue(company?.dividaliquida_PatrimonioLiquido.toString())
        val liabilitiesOverAssets = ValueUtils.getDoubleValue(responseDTO.otherIndicators?.passivosAtivos.toString())
        val marginLajir = ValueUtils.getDoubleValue(responseDTO.otherIndicators?.margemEbit.toString())

        return StockAnalisysDTO(
            ticker,
            companyIsInPerennialSector(sectorOfActivity),
            companyIsNotInJudicialRecovery(companyIsInJudicialRecovery),
            companyHasGoodLevelOfReturnOnEquity(returnOnEquity),
            companyHasGoodLevelOfProfitGrowthInTheLastFiveYears(cagrFiveYears),
            companyHasGoodNetMarginLevel(netMargin),
            companyHasGoodNetMarginLajir(marginLajir),
            companyHasGoodLevelCurrentLiquidity(currentLiquidity),
            companyHasGoodLevelNetDebitOverNetEquity(netDebitOverNetEquity),
            companyHasGoodLevelPriceOnBookValue(priceOnBookValue),
            companyHasGoodLevelLiabilitiesOverAssets(liabilitiesOverAssets),
            company?.bookName,
            company?.segmento_Atuacao
        )

    }

    private fun companyHasGoodNetMarginLajir(netMarginLajir: Double) =
        netMarginLajir.compareTo(configuration.minimoMargemLiquida.toDouble()) >= 1

    private fun companyHasGoodLevelLiabilitiesOverAssets(liabilitiesOverAssets: Double) =
        liabilitiesOverAssets.compareTo(1) <= 1

    private fun companyHasGoodLevelFreeFloat(freeFloat: Double) =
        freeFloat.compareTo(configuration.minimoFreeFloat.toDouble()) >= 1

    private fun companyHasGoodLevelOfProfitGrowthInTheLastFiveYears(cagr: Double) =
        cagr.compareTo(configuration.minimoCagrLucro5anos.toDouble()) >= 1

    private fun companyHasGoodLevelOfReturnOnEquity(returnOnEquity: Double) =
        returnOnEquity.compareTo(configuration.minimoROE.toDouble()) >= 1

    private fun companyIsInPerennialSector(sector: String) = configuration.setoresParenes.contains(sector)

    private fun companyHasGoodNetMarginLevel(netMargin: Double) =
        netMargin.compareTo(configuration.minimoMargemLiquida.toDouble()) >= 1

    private fun companyHasGoodLevelNetDebitOverNetEquity(netDebitOverNetEquity: Double) =
        netDebitOverNetEquity.compareTo(configuration.maximoDividaLiquidaSobrePatrimonioLiquido.toDouble()) < 1

    private fun companyHasGoodLevelOfNetDebtOverLajir(netDebtOverLajir: Double) =
        netDebtOverLajir.compareTo(0.00) >= 1 && netDebtOverLajir.compareTo(configuration.maximoDividaLiquidaSobreEbitda.toDouble()) <= 1

    private fun companyIsNotInJudicialRecovery(estaEmRecuperacaoJudicial: Boolean) = !estaEmRecuperacaoJudicial

    private fun companyHasGoodLevelCurrentLiquidity(currentLiquidity: Double) =
        currentLiquidity.compareTo(1.00) >= configuration.minimoLiquidez.toInt()

    private fun companyHasGoodLevelPriceOnBookValue(priceOnBookValue: Double) =
        companyHasGoodLevelOfPriceOverAssetValue(priceOnBookValue)

    private fun companyHasGoodLevelOfPriceOverAssetValue(priceOverAssetValue: Double) =
        priceOverAssetValue.compareTo(0.00) >= 1 && priceOverAssetValue in 0.10..configuration.maximoPrecoSobreValorPatrimonial.toDouble()

    private fun companyHasGoodLevelPriceProfit(priceProfit: Double) =
        priceProfit.compareTo(0.00) >= 1 && priceProfit in 0.10..configuration.maximoPrecoSobreLucro.toDouble()

    private fun hasRightToSellStocksEqualToControllingShareholder(tagAlong: Double) = tagAlong.toInt() == 100

}
