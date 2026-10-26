package com.hni.nfcidreader

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gemalto.jp2.JP2Decoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.sf.scuba.smartcards.CardService
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import java.io.DataInputStream
import java.security.Security

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var etDoc: EditText
    private lateinit var etBirth: EditText
    private lateinit var etExpiry: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var ivPhoto: ImageView
    private lateinit var progress: ProgressBar

    data class CardData(val text: String, val photo: Bitmap?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // نسخة BouncyCastle الكاملة بدل المزوّد المختصر في أندرويد
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        setContentView(R.layout.activity_main)
        etDoc = findViewById(R.id.etDocNumber)
        etBirth = findViewById(R.id.etBirth)
        etExpiry = findViewById(R.id.etExpiry)
        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)
        ivPhoto = findViewById(R.id.ivPhoto)
        progress = findViewById(R.id.progress)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) tvStatus.text = "هذا الهاتف لا يدعم NFC"
        else if (!nfcAdapter!!.isEnabled) tvStatus.text = "فعّل NFC من الإعدادات"
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getActivity(this, 0, intent, flags)
        adapter.enableForegroundDispatch(this, pi, null, arrayOf(arrayOf(IsoDep::class.java.name)))
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != NfcAdapter.ACTION_TECH_DISCOVERED) return
        val tag: Tag = (if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)) ?: return

        val doc = etDoc.text.toString().trim().uppercase()
        val birth = etBirth.text.toString().trim()
        val expiry = etExpiry.text.toString().trim()
        if (doc.isEmpty() || birth.length != 6 || expiry.length != 6) {
            tvStatus.text = "أدخل رقم البطاقة وتاريخي الميلاد والانتهاء بصيغة YYMMDD أولاً"
            return
        }

        progress.visibility = View.VISIBLE
        tvStatus.text = "جاري القراءة... لا تحرّك البطاقة"
        tvResult.text = ""
        ivPhoto.setImageDrawable(null)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { readCard(IsoDep.get(tag), BACKey(doc, birth, expiry)) }
            }
            progress.visibility = View.GONE
            result.onSuccess {
                tvStatus.text = "تمت القراءة بنجاح ✔"
                tvResult.text = it.text
                it.photo?.let { bmp -> ivPhoto.setImageBitmap(bmp) }
            }.onFailure {
                tvStatus.text = "فشلت القراءة: تأكد من البيانات وثبّت البطاقة\n(${it.javaClass.simpleName}: ${it.message})"
            }
        }
    }

    private fun readCard(isoDep: IsoDep, bacKey: BACKey): CardData {
        isoDep.timeout = 15000
        val cardService = CardService.getInstance(isoDep)
        cardService.open()
        val service = PassportService(
            cardService,
            PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
            PassportService.DEFAULT_MAX_BLOCKSIZE,
            false, false
        )
        service.open()

        // 1) محاولة PACE (البطاقات الحديثة)
        var paceOk = false
        try {
            val cardAccess = CardAccessFile(
                service.getInputStream(PassportService.EF_CARD_ACCESS, PassportService.DEFAULT_MAX_BLOCKSIZE)
            )
            for (info in cardAccess.securityInfos) {
                if (info is PACEInfo) {
                    service.doPACE(bacKey, info.objectIdentifier, PACEInfo.toParameterSpec(info.parameterId), null)
                    paceOk = true
                    break
                }
            }
        } catch (_: Exception) { }

        service.sendSelectApplet(paceOk)

        // 2) الرجوع إلى BAC إذا لم ينجح PACE
        if (!paceOk) {
            try {
                service.getInputStream(PassportService.EF_COM, PassportService.DEFAULT_MAX_BLOCKSIZE).read()
            } catch (_: Exception) {
                service.doBAC(bacKey)
            }
        }

        // DG1: بيانات MRZ
        val dg1 = DG1File(service.getInputStream(PassportService.EF_DG1, PassportService.DEFAULT_MAX_BLOCKSIZE))
        val mrz = dg1.mrzInfo

        val sb = StringBuilder()
        sb.appendLine("اللقب: ${mrz.primaryIdentifier.replace("<", " ").trim()}")
        sb.appendLine("الاسم: ${mrz.secondaryIdentifier.replace("<", " ").trim()}")
        sb.appendLine("الجنس: ${mrz.gender}")
        sb.appendLine("تاريخ الميلاد: ${fmt(mrz.dateOfBirth)}")
        sb.appendLine("الجنسية: ${mrz.nationality}")
        sb.appendLine("رقم الوثيقة: ${mrz.documentNumber}")
        sb.appendLine("تاريخ الانتهاء: ${fmt(mrz.dateOfExpiry)}")
        sb.appendLine("الدولة المصدِرة: ${mrz.issuingState}")
        mrz.personalNumber?.takeIf { it.isNotBlank() }?.let { sb.appendLine("الرقم الوطني: ${it.replace("<", "")}") }

        // DG11: بيانات إضافية (اختيارية، قد تحتوي الاسم بالعربية ومكان الميلاد)
        try {
            val dg11 = DG11File(service.getInputStream(PassportService.EF_DG11, PassportService.DEFAULT_MAX_BLOCKSIZE))
            dg11.nameOfHolder?.let { sb.appendLine("الاسم الكامل: ${it.replace("<", " ")}") }
            dg11.placeOfBirth?.takeIf { it.isNotEmpty() }?.let { sb.appendLine("مكان الميلاد: ${it.joinToString(" ")}") }
            dg11.permanentAddress?.takeIf { it.isNotEmpty() }?.let { sb.appendLine("العنوان: ${it.joinToString(" ")}") }
        } catch (_: Exception) { }

        // DG2: صورة الوجه
        var photo: Bitmap? = null
        try {
            val dg2 = DG2File(service.getInputStream(PassportService.EF_DG2, PassportService.DEFAULT_MAX_BLOCKSIZE))
            val face = dg2.faceInfos.flatMap { it.faceImageInfos }.firstOrNull()
            if (face != null) {
                val bytes = ByteArray(face.imageLength)
                DataInputStream(face.imageInputStream).readFully(bytes)
                photo = if (face.mimeType.contains("jp2") || face.mimeType.contains("jpeg2000"))
                    JP2Decoder(bytes).decode()
                else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Exception) { }

        try { service.close() } catch (_: Exception) { }
        return CardData(sb.toString(), photo)
    }

    private fun fmt(yymmdd: String): String =
        if (yymmdd.length == 6) "${yymmdd.substring(4, 6)}/${yymmdd.substring(2, 4)}/${yymmdd.substring(0, 2)}" else yymmdd
}
