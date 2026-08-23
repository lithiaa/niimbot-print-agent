package com.niimbot.printagent.pos

import com.niimbot.printagent.label.LabelData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosSubmissionWorkflowTest {
    private val form = LabelData("SKU-1", "Form name", 100L, 150L, 3, 4)
    private val posProduct = PosProduct("SKU-1", "Form name", 100L, 150L, stok = 10)
    private val operationId = "11111111-1111-4111-8111-111111111111"

    @Test
    fun `new SKU uses atomic create with incoming quantity then becomes queueable`() = runBlocking {
        val gateway = FakeGateway(lookupResult = PosApiResult.NotFound)
        gateway.createResult = PosApiResult.Success(posProduct.copy(stok = 4))

        val result = PosSubmissionWorkflow(gateway).submit("https://pos", "key", form, operationId)

        assertTrue(result is PosSubmissionOutcome.ReadyToQueue)
        assertEquals(listOf("lookup", "create:4:$operationId"), gateway.calls)
        result as PosSubmissionOutcome.ReadyToQueue
        assertEquals(3, result.labelData.qty)
        assertEquals(4, result.stockAdded)
        assertEquals(4, result.currentStock)
    }

    @Test
    fun `same existing data adds stock at form purchase price before queueing`() = runBlocking {
        val gateway = FakeGateway(lookupResult = PosApiResult.Success(posProduct))
        gateway.stockResult = PosApiResult.Success(posProduct.copy(stok = 14))

        val result = PosSubmissionWorkflow(gateway).submit("https://pos", "key", form, operationId)

        assertTrue(result is PosSubmissionOutcome.ReadyToQueue)
        assertEquals(listOf("lookup", "stock:4:100:$operationId"), gateway.calls)
        assertEquals(14, (result as PosSubmissionOutcome.ReadyToQueue).currentStock)
    }

    @Test
    fun `zero incoming stock still calls idempotent stock endpoint`() = runBlocking {
        val zeroStockForm = form.copy(jumlahBarangMasuk = 0)
        val gateway = FakeGateway(lookupResult = PosApiResult.Success(posProduct))
        gateway.stockResult = PosApiResult.Success(posProduct)

        val result = PosSubmissionWorkflow(gateway).submit(
            "https://pos",
            "key",
            zeroStockForm,
            operationId
        )

        assertTrue(result is PosSubmissionOutcome.ReadyToQueue)
        assertEquals(listOf("lookup", "stock:0:100:$operationId"), gateway.calls)
    }

    @Test
    fun `different data use POS choice adds stock using POS purchase price`() = runBlocking {
        val existing = posProduct.copy(nama = "POS name", hargaBeli = 90L)
        val gateway = FakeGateway(lookupResult = PosApiResult.Success(existing))
        gateway.stockResult = PosApiResult.Success(existing.copy(stok = 14))
        val workflow = PosSubmissionWorkflow(gateway)
        val conflict = workflow.submit("https://pos", "key", form, operationId)

        assertTrue(conflict is PosSubmissionOutcome.Conflict)
        val result = workflow.resolveConflict(
            conflict as PosSubmissionOutcome.Conflict,
            PosConflictChoice.USE_POS
        )

        assertTrue(result is PosSubmissionOutcome.ReadyToQueue)
        assertEquals(listOf("lookup", "stock:4:90:$operationId"), gateway.calls)
        assertEquals("POS name", (result as PosSubmissionOutcome.ReadyToQueue).labelData.nama)
    }

    @Test
    fun `different data update POS choice updates metadata then adds stock at form price`() = runBlocking {
        val existing = posProduct.copy(nama = "POS name", hargaBeli = 90L)
        val gateway = FakeGateway(lookupResult = PosApiResult.Success(existing))
        gateway.updateResult = PosApiResult.Success(posProduct)
        gateway.stockResult = PosApiResult.Success(posProduct.copy(stok = 14))
        val workflow = PosSubmissionWorkflow(gateway)
        val conflict = workflow.submit("https://pos", "key", form, operationId) as PosSubmissionOutcome.Conflict

        val result = workflow.resolveConflict(conflict, PosConflictChoice.UPDATE_POS)

        assertTrue(result is PosSubmissionOutcome.ReadyToQueue)
        assertEquals(listOf("lookup", "update", "stock:4:100:$operationId"), gateway.calls)
    }

    @Test
    fun `cancel conflict performs no writes and is not queueable`() = runBlocking {
        val gateway = FakeGateway(
            lookupResult = PosApiResult.Success(posProduct.copy(nama = "Different"))
        )
        val workflow = PosSubmissionWorkflow(gateway)
        val conflict = workflow.submit("https://pos", "key", form, operationId) as PosSubmissionOutcome.Conflict

        val result = workflow.resolveConflict(conflict, PosConflictChoice.CANCEL)

        assertEquals(PosSubmissionOutcome.Cancelled, result)
        assertEquals(listOf("lookup"), gateway.calls)
    }

    @Test
    fun `any POS write failure is not queueable and update failure skips stock`() = runBlocking {
        val existing = posProduct.copy(nama = "Different")
        val gateway = FakeGateway(lookupResult = PosApiResult.Success(existing))
        gateway.updateResult = PosApiResult.Failure("update failed")
        val workflow = PosSubmissionWorkflow(gateway)
        val conflict = workflow.submit("https://pos", "key", form, operationId) as PosSubmissionOutcome.Conflict

        val result = workflow.resolveConflict(conflict, PosConflictChoice.UPDATE_POS)

        assertEquals(PosSubmissionOutcome.Failure("update failed"), result)
        assertEquals(listOf("lookup", "update"), gateway.calls)
    }

    @Test
    fun `stock failure is not queueable`() = runBlocking {
        val gateway = FakeGateway(lookupResult = PosApiResult.Success(posProduct))
        gateway.stockResult = PosApiResult.Failure("stock failed")

        val result = PosSubmissionWorkflow(gateway).submit("https://pos", "key", form, operationId)

        assertEquals(PosSubmissionOutcome.Failure("stock failed"), result)
        assertEquals(listOf("lookup", "stock:4:100:$operationId"), gateway.calls)
    }

    private class FakeGateway(
        var lookupResult: PosApiResult<PosProduct>
    ) : PosProductGateway {
        val calls = mutableListOf<String>()
        var createResult: PosApiResult<PosProduct> = PosApiResult.Failure("unexpected create")
        var updateResult: PosApiResult<PosProduct> = PosApiResult.Failure("unexpected update")
        var stockResult: PosApiResult<PosProduct> = PosApiResult.Failure("unexpected stock")

        override suspend fun lookup(baseUrl: String, integrationKey: String, normalizedSku: String): PosApiResult<PosProduct> {
            calls += "lookup"
            return lookupResult
        }

        override suspend fun create(baseUrl: String, integrationKey: String, form: LabelData, operationId: String): PosApiResult<PosProduct> {
            calls += "create:${form.jumlahBarangMasuk}:$operationId"
            return createResult
        }

        override suspend fun update(baseUrl: String, integrationKey: String, form: LabelData): PosApiResult<PosProduct> {
            calls += "update"
            return updateResult
        }

        override suspend fun addStock(
            baseUrl: String,
            integrationKey: String,
            sku: String,
            jumlahBarangMasuk: Int,
            hargaSatuan: Long,
            operationId: String
        ): PosApiResult<PosProduct> {
            calls += "stock:$jumlahBarangMasuk:$hargaSatuan:$operationId"
            return stockResult
        }
    }
}
