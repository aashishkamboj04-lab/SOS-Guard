package com.example.sosapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.Location
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.telephony.SmsManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.json.JSONArray
import java.util.*

class MainActivity : AppCompatActivity() {

    private val contacts = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLocation: Location? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isSendingSOS = false

    enum class AccidentState {
        NORMAL, COUNTDOWN_RUNNING, SOS_TRIGGERED
    }
    private var accidentState = AccidentState.NORMAL
    private var countdownTimer: CountDownTimer? = null
    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var countdownDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val manualInput = findViewById<EditText>(R.id.manualNumber)
        val addManualBtn = findViewById<Button>(R.id.addManualBtn)
        val addContactBtn = findViewById<Button>(R.id.addContactBtn)
        val sosBtn = findViewById<Button>(R.id.sosButton)
        val listView = findViewById<ListView>(R.id.contactList)
        val statusText = findViewById<TextView>(R.id.statusText)

        // Start background accident detection service
        val serviceIntent = Intent(this, SOSBackgroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // 🔥 FIXED WHITE TEXT LIST
        adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, contacts) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val text = view.findViewById<TextView>(android.R.id.text1)
                text.setTextColor(0xFFFFFFFF.toInt())
                return view
            }
        }

        listView.adapter = adapter

        loadContacts()

        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE)
        }

        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            1
        )

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        fusedLocationClient.lastLocation.addOnSuccessListener {
            if (it != null) {
                currentLocation = it
            }
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000).build()
        fusedLocationClient.requestLocationUpdates(request,
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    currentLocation = result.lastLocation
                }
            }, mainLooper)

        startVoiceListening(statusText)

        addManualBtn.setOnClickListener {
            val num = manualInput.text.toString()
            if (num.isNotEmpty()) {
                contacts.add(num)
                adapter.notifyDataSetChanged()
                saveContacts()
                manualInput.text.clear()
            }
        }

        addContactBtn.setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
                100
            )
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            contacts.removeAt(position)
            adapter.notifyDataSetChanged()
            saveContacts()
        }

        sosBtn.setOnClickListener {
            cancelAccidentCountdown()
            Log.d("SOSAccidentDetector", "SOS triggered")
            sendEmergencySOS()
        }

        if (intent?.getBooleanExtra("START_ACCIDENT_COUNTDOWN", false) == true) {
            startAccidentCountdown()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("START_ACCIDENT_COUNTDOWN", false)) {
            startAccidentCountdown()
        }
    }

    private fun startAccidentCountdown() {
        if (accidentState != AccidentState.NORMAL) return
        if (contacts.isEmpty()) return

        Log.d("SOSAccidentDetector", "Countdown started")
        accidentState = AccidentState.COUNTDOWN_RUNNING
        playWarningSound()

        val statusText = findViewById<TextView>(R.id.statusText)
        statusText.text = "Possible accident detected! Countdown started."

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            gravity = Gravity.CENTER
        }

        val messageView = TextView(this).apply {
            text = "Possible accident detected\n\nSOS will be sent automatically in 15 seconds."
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        dialogView.addView(messageView)

        val builder = AlertDialog.Builder(this)
            .setTitle("🚨 POSSIBLE ACCIDENT")
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("CANCEL") { dialog, _ ->
                cancelAccidentCountdown()
                statusText.text = "Accident alert cancelled"
                dialog.dismiss()
            }

        countdownDialog = builder.create().apply {
            window?.decorView?.setBackgroundColor(0xFF0B1220.toInt())
            show()
        }

        countdownTimer = object : CountDownTimer(15000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                messageView.text = "Possible accident detected\n\nSOS will be sent automatically in $seconds seconds."
            }

            override fun onFinish() {
                countdownDialog?.dismiss()
                stopWarningSound()
                accidentState = AccidentState.SOS_TRIGGERED
                Log.d("SOSAccidentDetector", "SOS triggered")
                statusText.text = "Accident detected! Sending SOS..."
                sendEmergencySOS()

                handler.postDelayed({
                    accidentState = AccidentState.NORMAL
                }, 3000)
            }
        }.start()
    }

    private fun cancelAccidentCountdown() {
        if (accidentState == AccidentState.COUNTDOWN_RUNNING) {
            Log.d("SOSAccidentDetector", "Countdown cancelled")
        }
        countdownTimer?.cancel()
        countdownTimer = null
        countdownDialog?.dismiss()
        countdownDialog = null
        stopWarningSound()
        if (accidentState == AccidentState.COUNTDOWN_RUNNING) {
            accidentState = AccidentState.NORMAL
        }
    }

    private fun playWarningSound() {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mediaPlayer = MediaPlayer.create(applicationContext, alarmUri).apply {
                isLooping = true
                start()
            }
        } catch (_: Exception) {
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 15000)
            } catch (_: Exception) {}
        }
    }

    private fun stopWarningSound() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {}
        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {}
    }

    private fun startVoiceListening(statusText: TextView) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())

        try {
            statusText.text = "Listening..."
            startActivityForResult(intent, 200)
        } catch (e: Exception) {
            statusText.text = "Voice not supported"
        }
    }

    private fun getLocation(): String {
        val loc = currentLocation
        if (loc != null) {
            val lat = loc.latitude
            val lon = loc.longitude
            return "https://maps.google.com/?q=$lat,$lon"
        }
        return ""
    }

    private fun sendEmergencySOS() {
        if (isSendingSOS) return
        isSendingSOS = true

        val statusText = findViewById<TextView>(R.id.statusText)
        val modeGroup = findViewById<RadioGroup>(R.id.modeGroup)

        if (contacts.isEmpty()) {
            statusText.text = "No contacts"
            isSendingSOS = false
            return
        }

        val locUrl = getLocation()
        val msg = if (locUrl.isNotEmpty()) {
            "🚨 HELP! My Location:\n$locUrl"
        } else {
            "🚨 HELP! Location unavailable."
        }

        if (modeGroup.checkedRadioButtonId == R.id.whatsappOption) {
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, msg)
            intent.setPackage("com.whatsapp")
            try {
                startActivity(intent)
                statusText.text = "WhatsApp opened"
            } catch (e: Exception) {
                statusText.text = "WhatsApp not installed"
            }
            isSendingSOS = false
        } else {
            // SMS Mode (Direct via SmsManager)
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.SEND_SMS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                statusText.text = "SMS permission required"
                isSendingSOS = false
                return
            }

            try {
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

                for (contact in contacts) {
                    smsManager.sendTextMessage(contact, null, msg, null, null)
                }
                statusText.text = "SOS SMS sent to all contacts"
            } catch (e: Exception) {
                statusText.text = "Failed to send SMS: ${e.localizedMessage}"
            }

            handler.postDelayed({ isSendingSOS = false }, 2000)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 100 && resultCode == RESULT_OK) {
            val cursor: Cursor? = contentResolver.query(data!!.data!!, null, null, null, null)
            cursor?.moveToFirst()
            val number = cursor?.getString(
                cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            )
            contacts.add(number!!)
            adapter.notifyDataSetChanged()
            saveContacts()
        }

        if (requestCode == 200 && resultCode == RESULT_OK) {
            val result = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = result?.get(0)?.lowercase() ?: ""

            if (text.contains("help") || text.contains("bachao")) {
                cancelAccidentCountdown()
                Log.d("SOSAccidentDetector", "SOS triggered")
                sendEmergencySOS()
            } else {
                startVoiceListening(findViewById(R.id.statusText))
            }
        }
    }

    private fun saveContacts() {
        val json = JSONArray(contacts)
        getSharedPreferences("SOS", MODE_PRIVATE)
            .edit().putString("list", json.toString()).apply()
    }

    private fun loadContacts() {
        val data = getSharedPreferences("SOS", MODE_PRIVATE)
            .getString("list", null)

        if (data != null) {
            val json = JSONArray(data)
            for (i in 0 until json.length()) {
                contacts.add(json.getString(i))
            }
        }
    }
}
