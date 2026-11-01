package dev.leandro.easydrop.lab

import org.junit.Assert.assertEquals
import org.junit.Test

class LabStatusTest {

    @Test
    fun `troca de modo e papel vale parado`() {
        val status = LabStatus(phase = Phase.IDLE, mode = Mode.LAN, role = Role.SENDER)

        assertEquals(Mode.P2P, status.choose(mode = Mode.P2P).mode)
        assertEquals(Role.RECEIVER, status.choose(role = Role.RECEIVER).role)
    }

    @Test
    fun `troca e ignorada durante a busca`() {
        val status = LabStatus(phase = Phase.SEARCHING, mode = Mode.LAN, role = Role.SENDER)

        assertEquals(status, status.choose(mode = Mode.P2P, role = Role.RECEIVER))
    }

    @Test
    fun `troca depois de erro volta para idle`() {
        val status = LabStatus(phase = Phase.ERROR, detail = "falhou", mode = Mode.LAN, role = Role.SENDER)

        val chosen = status.choose(mode = Mode.P2P)

        assertEquals(Phase.IDLE, chosen.phase)
        assertEquals(Mode.P2P, chosen.mode)
        assertEquals(Role.SENDER, chosen.role)
    }
}
