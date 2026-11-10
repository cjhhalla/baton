package com.baton.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * 반납 증명서.
 * 반납 기록을 차 안에서만 꺼낼 수 있는 키(Android Keystore, EC P-256)로 서명하고,
 * 기록 + 서명 + 공개키를 URL 하나에 담아 QR로 보여 준다.
 * 폰으로 찍으면 증명서 페이지가 서명을 검증해 "차가 서명한 기록"인지 확인한다.
 * 업체는 차량별 공개키 지문(키 ID)을 등록해 두면 다른 키로 만든 가짜 기록을 걸러낼 수 있다.
 */
object ReturnProof {

    /** 증명서 페이지 주소. GitHub Pages 등에 verify 페이지를 올린 뒤 이 값을 바꾼다 */
    const val VERIFY_URL = "https://cjhhalla.github.io/baton/verify/"

    private const val ALIAS = "baton_return_proof"

    private fun b64(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun key(): Pair<PrivateKey, ByteArray> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            gen.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            gen.generateKeyPair()
        }
        val entry = ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.privateKey to entry.certificate.publicKey.encoded // X.509 SubjectPublicKeyInfo
    }

    /** 증명서에 담는 내용 (짧은 키 이름으로 QR 크기를 줄인다) */
    fun payload(s: Snapshot, carName: String): String = JSONObject()
        .put("v", 1)
        .put("car", carName)
        .put("drv", s.driver)
        .put("pick", s.pickedUpAt ?: JSONObject.NULL)
        .put("ret", s.takenAt)
        .put("due", s.deadline ?: JSONObject.NULL)
        .put("soc", s.socPercent?.toInt() ?: JSONObject.NULL)
        .put("fuel", s.fuelPercent?.toInt() ?: JSONObject.NULL)
        .put("odo", s.odometerKm?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL)
        .put("trip", s.tripKm?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL)
        .put("spot", s.atReturnSpot ?: JSONObject.NULL)
        .put("dist", s.returnDistanceM?.toInt() ?: JSONObject.NULL)
        .put("iss", JSONArray(s.issues.map { JSONArray(listOf(it.kind.name, it.title, it.detail)) }))
        .put("warn", JSONArray(s.warnings.map { it.label }))
        .toString()

    /** 서명해서 QR에 넣을 URL을 만든다. 실패하면 null (서명 없는 기록은 증명서로 쓰지 않는다) */
    fun create(s: Snapshot, carName: String): String? = runCatching {
        val body = payload(s, carName).toByteArray(Charsets.UTF_8)
        val (priv, pub) = key()
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(priv); update(body); sign() // DER
        }
        "$VERIFY_URL#${b64(body)}.${b64(sig)}.${b64(pub)}"
    }.getOrNull()
}
