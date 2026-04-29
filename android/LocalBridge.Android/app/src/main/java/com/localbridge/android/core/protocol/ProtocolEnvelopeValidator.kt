package com.localbridge.android.core.protocol

data class EnvelopeValidationResult(
    val isValid: Boolean,
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    companion object {
        val Valid = EnvelopeValidationResult(isValid = true)
    }
}

object ProtocolEnvelopeValidator {
    fun <T> validate(
        envelope: ProtocolEnvelope<T>?,
        requirePayload: Boolean = true,
        vararg expectedPacketTypes: String
    ): EnvelopeValidationResult {
        if (envelope == null) {
            return EnvelopeValidationResult(
                isValid = false,
                errorCode = ProtocolErrorCodes.invalidRequest,
                errorMessage = "Protocol envelope is missing."
            )
        }

        if (envelope.meta.version != ProtocolConstants.version) {
            return EnvelopeValidationResult(
                isValid = false,
                errorCode = ProtocolErrorCodes.protocolMismatch,
                errorMessage = "Protocol version mismatch."
            )
        }

        if (expectedPacketTypes.isNotEmpty() && expectedPacketTypes.none { it == envelope.meta.packetType }) {
            return EnvelopeValidationResult(
                isValid = false,
                errorCode = ProtocolErrorCodes.invalidRequest,
                errorMessage = "Unexpected packet type ${envelope.meta.packetType}."
            )
        }

        if (requirePayload && envelope.payload == null) {
            return EnvelopeValidationResult(
                isValid = false,
                errorCode = ProtocolErrorCodes.invalidRequest,
                errorMessage = "Protocol payload is missing."
            )
        }

        return EnvelopeValidationResult.Valid
    }
}
