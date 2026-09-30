package com.niimbot.printagent.pos

import com.niimbot.printagent.label.LabelData
import java.util.concurrent.CancellationException

enum class PosConflictChoice {
    USE_POS,
    UPDATE_POS,
    CANCEL
}

sealed interface PosSubmissionOutcome {
    data class ReadyToQueue(
        val labelData: LabelData,
        val stockAdded: Int,
        val currentStock: Int,
        val productId: Long? = null
    ) : PosSubmissionOutcome

    data class Conflict(
        val baseUrl: String,
        val accessToken: String,
        val form: LabelData,
        val product: PosProduct,
        val operationId: String
    ) : PosSubmissionOutcome

    data class Failure(val message: String) : PosSubmissionOutcome
    data object SessionExpired : PosSubmissionOutcome
    data object Cancelled : PosSubmissionOutcome
}

class PosSubmissionWorkflow(private val gateway: PosProductGateway) {
    suspend fun submit(
        baseUrl: String,
        accessToken: String,
        form: LabelData,
        operationId: String
    ): PosSubmissionOutcome {
        return when (val lookup = safeRequest {
            gateway.lookup(baseUrl, accessToken, form.sku)
        }) {
            PosApiResult.NotFound -> create(baseUrl, accessToken, form, operationId)
            is PosApiResult.Success -> {
                if (PosProductRules.decideExisting(form, lookup.value) == PosLookupDecision.SHOW_CONFLICT) {
                    PosSubmissionOutcome.Conflict(
                        baseUrl,
                        accessToken,
                        form,
                        lookup.value,
                        operationId
                    )
                } else {
                    addStock(
                        baseUrl,
                        accessToken,
                        form,
                        lookup.value,
                        lookup.value.hargaBeli,
                        operationId,
                        useProductData = false
                    )
                }
            }
            PosApiResult.SessionExpired -> PosSubmissionOutcome.SessionExpired
            is PosApiResult.Failure -> PosSubmissionOutcome.Failure(lookup.message)
        }
    }

    suspend fun resolveConflict(
        conflict: PosSubmissionOutcome.Conflict,
        choice: PosConflictChoice
    ): PosSubmissionOutcome = when (choice) {
        PosConflictChoice.CANCEL -> PosSubmissionOutcome.Cancelled
        PosConflictChoice.USE_POS -> addStock(
            conflict.baseUrl,
            conflict.accessToken,
            conflict.form,
            conflict.product,
            conflict.product.hargaBeli,
            conflict.operationId,
            useProductData = true
        )
        PosConflictChoice.UPDATE_POS -> updateThenAddStock(conflict)
    }

    private suspend fun create(
        baseUrl: String,
        accessToken: String,
        form: LabelData,
        operationId: String
    ): PosSubmissionOutcome = when (val result = safeRequest {
        gateway.create(baseUrl, accessToken, form, operationId)
    }) {
        is PosApiResult.Success -> ready(form, result.value, useProductData = false)
        PosApiResult.SessionExpired -> PosSubmissionOutcome.SessionExpired
        is PosApiResult.Failure -> PosSubmissionOutcome.Failure(result.message)
        PosApiResult.NotFound -> genericFailure()
    }

    private suspend fun updateThenAddStock(
        conflict: PosSubmissionOutcome.Conflict
    ): PosSubmissionOutcome = when (val result = safeRequest {
        gateway.update(conflict.baseUrl, conflict.accessToken, conflict.form, conflict.product)
    }) {
        is PosApiResult.Success -> addStock(
            conflict.baseUrl,
            conflict.accessToken,
            conflict.form,
            result.value,
            conflict.form.hargaBeli,
            conflict.operationId,
            useProductData = false
        )
        PosApiResult.SessionExpired -> PosSubmissionOutcome.SessionExpired
        is PosApiResult.Failure -> PosSubmissionOutcome.Failure(result.message)
        PosApiResult.NotFound -> genericFailure()
    }

    private suspend fun addStock(
        baseUrl: String,
        accessToken: String,
        form: LabelData,
        product: PosProduct,
        hargaSatuan: Long,
        operationId: String,
        useProductData: Boolean
    ): PosSubmissionOutcome = when (val result = safeRequest {
        gateway.addStock(
            baseUrl,
            accessToken,
            product,
            form.jumlahBarangMasuk,
            hargaSatuan,
            operationId
        )
    }) {
        is PosApiResult.Success -> ready(form, result.value, useProductData)
        PosApiResult.SessionExpired -> PosSubmissionOutcome.SessionExpired
        is PosApiResult.Failure -> PosSubmissionOutcome.Failure(result.message)
        PosApiResult.NotFound -> genericFailure()
    }

    private fun ready(
        form: LabelData,
        product: PosProduct,
        useProductData: Boolean
    ): PosSubmissionOutcome.ReadyToQueue {
        val labelData = if (useProductData) {
            PosProductRules.toLabelData(product, form.qty, form.jumlahBarangMasuk).copy(
                labelSize = form.labelSize,
                kodeHargaBeli = form.kodeHargaBeli,
                itemQty = form.itemQty,
                supplierCode = form.supplierCode,
                tanggalMasuk = form.tanggalMasuk,
                supplierId = form.supplierId
            )
        } else {
            form
        }
        return PosSubmissionOutcome.ReadyToQueue(
            labelData = labelData,
            stockAdded = form.jumlahBarangMasuk,
            currentStock = product.stok,
            productId = product.id
        )
    }

    private suspend fun safeRequest(
        request: suspend () -> PosApiResult<PosProduct>
    ): PosApiResult<PosProduct> = try {
        request()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        PosApiResult.Failure(error.message ?: GENERIC_FAILURE)
    }

    private fun genericFailure() = PosSubmissionOutcome.Failure(GENERIC_FAILURE)

    private companion object {
        const val GENERIC_FAILURE = "Permintaan ke Sistem gagal. Label tidak dicetak."
    }
}
