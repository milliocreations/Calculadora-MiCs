package com.milliocreations.micscalculator

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.*

private data class CalcSnapshot(
    val display: String,
    val expression: String,
    val previousValue: Double?,
    val operation: String?,
    val newNumber: Boolean,
    val degrees: Boolean,
    val inverse: Boolean,
    val expMode: Boolean,
    val expBase: Double?,
    val memoryValue: Double,
    val hasMemory: Boolean
)

private data class CalculatorPalette(
    val background: Color,
    val surface: Color,
    val text: Color,
    val secondaryText: Color,
    val number: Color,
    val operator: Color,
    val scientific: Color,
    val accent: Color,
    val selected: Color,
    val border: Color
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MiCsCalculator() }
    }
}

@Composable
fun MiCsCalculator() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mics_preferences", android.content.Context.MODE_PRIVATE) }
    var display by remember { mutableStateOf("0") }
    var expression by remember { mutableStateOf("") }
    var previousValue by remember { mutableStateOf<Double?>(null) }
    var operation by remember { mutableStateOf<String?>(null) }
    var newNumber by remember { mutableStateOf(true) }
    var degrees by remember { mutableStateOf(prefs.getBoolean("degrees", true)) }
    var inverse by remember { mutableStateOf(false) }
    var expMode by remember { mutableStateOf(false) }
    var expBase by remember { mutableStateOf<Double?>(null) }
    var memoryValue by remember { mutableDoubleStateOf(0.0) }
    var hasMemory by remember { mutableStateOf(false) }
    var pressedKey by remember { mutableStateOf<String?>(null) }
    var selectedOperationKey by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf("calculator") }
    var menuOpen by remember { mutableStateOf(false) }
    var undoDialog by remember { mutableStateOf(false) }
    var colorMenuOpen by remember { mutableStateOf(false) }
    var themeColorName by remember { mutableStateOf(prefs.getString("theme_color", "Azul MiCs") ?: "Azul MiCs") }
    var appearance by remember { mutableStateOf(prefs.getString("appearance", "system") ?: "system") }
    val undoStack = remember { mutableStateListOf<CalcSnapshot>() }

    val themeColors = remember {
        linkedMapOf(
            "Azul MiCs" to Color(0xFF356FF0),
            "Rojo" to Color(0xFFD83B45),
            "Naranja" to Color(0xFFE87500),
            "Amarillo" to Color(0xFFE0AD00),
            "Verde" to Color(0xFF268B57),
            "Turquesa" to Color(0xFF009E9A),
            "Celeste" to Color(0xFF168CCB),
            "Azul" to Color(0xFF2463D3),
            "Violeta" to Color(0xFF7444D8),
            "Rosa" to Color(0xFFD84C91),
            "Magenta" to Color(0xFFAD3EC2),
            "Gris" to Color(0xFF626B78)
        )
    }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = when (appearance) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
    val baseAccent = themeColors[themeColorName] ?: Color(0xFF356FF0)
    val palette = remember(baseAccent, dark) { createPalette(baseAccent, dark) }

    LaunchedEffect(pressedKey) {
        if (pressedKey != null) {
            delay(150)
            pressedKey = null
        }
    }

    fun formatNumber(number: Double): String {
        if (number.isNaN() || number.isInfinite()) return "Error"
        return if (number % 1.0 == 0.0 && abs(number) < 1.0e15) {
            number.toLong().toString()
        } else {
            String.format(Locale.US, "%.10f", number).trimEnd('0').trimEnd('.')
        }
    }

    fun currentNumber(): Double = display.toDoubleOrNull() ?: 0.0

    fun snapshot() = CalcSnapshot(display, expression, previousValue, operation, newNumber,
        degrees, inverse, expMode, expBase, memoryValue, hasMemory)

    fun rememberUndo() {
        undoStack.add(snapshot())
        if (undoStack.size > 40) undoStack.removeAt(0)
    }

    fun tap(key: String, action: () -> Unit) {
        rememberUndo()
        pressedKey = key
        action()
    }

    fun calculate() {
        if (expMode) {
            val exponent = currentNumber()
            val base = expBase ?: 0.0
            display = formatNumber(base * 10.0.pow(exponent))
            expression = "${formatNumber(base)} × 10^${formatNumber(exponent)} ="
            expMode = false
            expBase = null
            newNumber = true
            selectedOperationKey = null
            return
        }
        val first = previousValue ?: return
        val second = currentNumber()
        val op = operation
        val result = when (op) {
            "+" -> first + second
            "-" -> first - second
            "×" -> first * second
            "÷" -> if (second != 0.0) first / second else Double.NaN
            "^" -> first.pow(second)
            else -> second
        }
        val symbol = if (op == "-") "−" else op.orEmpty()
        expression = "${formatNumber(first)} $symbol ${formatNumber(second)} ="
        display = formatNumber(result)
        previousValue = null
        operation = null
        newNumber = true
        selectedOperationKey = null
    }

    fun pressNumber(number: String) {
        if (newNumber || display == "0" || display == "Error") {
            if (operation == null && !expMode) expression = ""
            display = number
            newNumber = false
        } else {
            display += number
        }
    }

    fun pressDecimal() {
        if (newNumber || display == "Error") {
            if (operation == null && !expMode) expression = ""
            display = "0."
            newNumber = false
        } else if (!display.contains('.')) display += "."
    }

    fun pressBackspace() {
        if (display == "Error" || display.length <= 1 || (display.length == 2 && display.startsWith("-"))) {
            display = "0"
        } else display = display.dropLast(1)
        newNumber = false
    }

    fun pressOperation(op: String) {
        if (previousValue != null && !newNumber) calculate()
        previousValue = currentNumber()
        operation = op
        val shown = if (op == "-") "−" else if (op == "^") "^" else op
        expression = "${formatNumber(previousValue ?: 0.0)} $shown"
        selectedOperationKey = when (op) { "-" -> "−"; "^" -> "xʸ"; else -> op }
        newNumber = true
    }

    fun pressExp() {
        expBase = currentNumber()
        expMode = true
        expression = "${formatNumber(expBase ?: 0.0)} × 10^"
        display = "0"
        newNumber = false
        selectedOperationKey = "EXP"
    }

    fun scientific(type: String) {
        val number = currentNumber()
        val wasInverse = inverse
        val result = when (type) {
            "sin" -> if (wasInverse) { val v = asin(number); if (degrees) Math.toDegrees(v) else v } else sin(if (degrees) Math.toRadians(number) else number)
            "cos" -> if (wasInverse) { val v = acos(number); if (degrees) Math.toDegrees(v) else v } else cos(if (degrees) Math.toRadians(number) else number)
            "tan" -> if (wasInverse) { val v = atan(number); if (degrees) Math.toDegrees(v) else v } else tan(if (degrees) Math.toRadians(number) else number)
            "ln" -> if (wasInverse) exp(number) else ln(number)
            "log" -> if (wasInverse) 10.0.pow(number) else log10(number)
            "√" -> sqrt(number)
            "x!" -> if (number < 0 || number > 170 || number % 1.0 != 0.0) Double.NaN else { var f = 1.0; for (i in 1..number.toInt()) f *= i; f }
            "π" -> Math.PI
            "e" -> Math.E
            "%" -> number / 100.0
            "±" -> -number
            else -> number
        }
        val label = when (type) {
            "sin" -> if (wasInverse) "sin⁻¹" else "sin"
            "cos" -> if (wasInverse) "cos⁻¹" else "cos"
            "tan" -> if (wasInverse) "tan⁻¹" else "tan"
            "ln" -> if (wasInverse) "eˣ" else "ln"
            "log" -> if (wasInverse) "10ˣ" else "log"
            else -> type
        }
        expression = "$label(${formatNumber(number)}) ="
        display = formatNumber(result)
        newNumber = true
        if (type in listOf("sin", "cos", "tan", "ln", "log")) inverse = false
    }

    fun doUndo() {
        if (undoStack.isNotEmpty()) {
            val s = undoStack.removeAt(undoStack.lastIndex)
            display = s.display; expression = s.expression; previousValue = s.previousValue
            operation = s.operation; newNumber = s.newNumber; degrees = s.degrees
            inverse = s.inverse; expMode = s.expMode; expBase = s.expBase
            memoryValue = s.memoryValue; hasMemory = s.hasMemory
            selectedOperationKey = operation?.let { when (it) { "-" -> "−"; "^" -> "xʸ"; else -> it } }
        }
    }

    fun chooseTheme(name: String) {
        themeColorName = name
        prefs.edit().putString("theme_color", name).apply()
        colorMenuOpen = false
    }

    fun chooseAppearance(value: String) {
        appearance = value
        prefs.edit().putString("appearance", value).apply()
    }

    fun sendFeedback() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Comentarios sobre MiCs Calculator")
            putExtra(Intent.EXTRA_TEXT, "Comentarios sobre MiCs Calculator:\n\n")
        }
        context.startActivity(Intent.createChooser(intent, "Enviar comentarios"))
    }

    fun rateApp() {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}")))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}")))
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = palette.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val large = maxWidth >= 600.dp
            val sideWidth = maxWidth * if (large) 0.48f else 0.78f

            when (screen) {
                "calculator" -> CalculatorScreen(
                    display = display, expression = expression, palette = palette, large = large,
                    maxHeight = maxHeight, pressedKey = pressedKey, selectedOperationKey = selectedOperationKey,
                    degrees = degrees, inverse = inverse, hasMemory = hasMemory,
                    onMenu = { menuOpen = true }, onUndo = { tap("undo") { doUndo() } },
                    onKey = { key -> tap(key) {
                        when (key) {
                            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9" -> pressNumber(key)
                            "." -> pressDecimal()
                            "⌫" -> pressBackspace()
                            "CE" -> { display = "0"; newNumber = true }
                            "C" -> { display = "0"; expression = ""; previousValue = null; operation = null; newNumber = true; inverse = false; expMode = false; expBase = null; selectedOperationKey = null }
                            "1/x" -> { val n = currentNumber(); expression = "1/(${formatNumber(n)}) ="; display = formatNumber(if (n != 0.0) 1.0 / n else Double.NaN); newNumber = true }
                            "x²" -> { val n = currentNumber(); expression = "(${formatNumber(n)})² ="; display = formatNumber(n * n); newNumber = true }
                            "Deg/Rad" -> { degrees = !degrees; prefs.edit().putBoolean("degrees", degrees).apply() }
                            "Inv" -> inverse = !inverse
                            "sin", "cos", "tan", "ln", "log", "√", "x!", "π", "e", "%", "±" -> scientific(key)
                            "EXP" -> pressExp()
                            "xʸ" -> pressOperation("^")
                            "÷" -> pressOperation("÷")
                            "×" -> pressOperation("×")
                            "−" -> pressOperation("-")
                            "+" -> pressOperation("+")
                            "=" -> calculate()
                            "(" -> { expression += if (expression.isNotEmpty()) " (" else "("; display = "0"; newNumber = true }
                            ")" -> { expression += ")" }
                        }
                    } },
                    onMemory = { key -> tap(key) {
                        when (key) {
                            "MC" -> { memoryValue = 0.0; hasMemory = false }
                            "MR" -> if (hasMemory) { display = formatNumber(memoryValue); newNumber = true }
                            "M+" -> { memoryValue += currentNumber(); hasMemory = true }
                            "M−" -> { memoryValue -= currentNumber(); hasMemory = true }
                            "MS" -> { memoryValue = currentNumber(); hasMemory = true }
                        }
                    } }
                )
                "theme" -> ThemeScreen(
                    palette = palette, colors = themeColors, selected = themeColorName,
                    expanded = colorMenuOpen, onExpanded = { colorMenuOpen = it }, onChoose = ::chooseTheme,
                    onBack = { screen = "calculator" }
                )
                "settings" -> SettingsScreen(
                    palette = palette, appearance = appearance, degrees = degrees,
                    onAppearance = ::chooseAppearance,
                    onDegrees = { degrees = it; prefs.edit().putBoolean("degrees", it).apply() },
                    onBack = { screen = "calculator" }
                )
                "about" -> InfoScreen(palette, "Acerca de MiCs", "MiCs Calculator", appVersion(context), "Desarrollada por Millio Creations.", { screen = "calculator" })
                "feedback" -> InfoScreen(palette, "Enviar comentarios", "Ayúdanos a mejorar MiCs Calculator", "", "Puedes compartir una sugerencia o informar de un error mediante una aplicación compatible instalada en tu dispositivo.", { screen = "calculator" }, actionLabel = "Compartir comentarios", onAction = ::sendFeedback)
                "rate" -> InfoScreen(palette, "Calificar aplicación", "¿Te gusta MiCs Calculator?", "", "Tu valoración en Google Play ayuda a que otras personas conozcan la aplicación.", { screen = "calculator" }, actionLabel = "Abrir Google Play", onAction = ::rateApp)
            }

            if (menuOpen) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (dark) 0.48f else 0.30f)).clickable { menuOpen = false })
                Column(
                    modifier = Modifier.width(sideWidth).fillMaxHeight().align(Alignment.CenterStart)
                        .background(palette.surface, RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp))
                        .padding(vertical = 12.dp, horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("☰", modifier = Modifier.fillMaxWidth().clickable { menuOpen = false }.padding(8.dp), color = palette.text, fontSize = 24.sp)
                    Text("MiCs Calculator", modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 8.dp), color = palette.secondaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    val items = listOf(
                        Triple("calculator", "▦", "Calculadora"),
                        Triple("theme", "◉", "Tema de color"),
                        Triple("settings", "⚙", "Configuración"),
                        Triple("feedback", "✉", "Enviar comentarios"),
                        Triple("rate", "☆", "Calificar aplicación"),
                        Triple("about", "ⓘ", "Acerca de MiCs")
                    )
                    items.forEach { (id, glyph, label) ->
                        val active = screen == id
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                                .background(if (active) palette.selected else Color.Transparent)
                                .clickable { screen = id; menuOpen = false }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (active) Box(Modifier.width(3.dp).height(24.dp).clip(RoundedCornerShape(4.dp)).background(palette.accent))
                            else Spacer(Modifier.width(3.dp))
                            Text(glyph, color = if (active) palette.accent else palette.text, fontSize = 19.sp)
                            Text(label, color = if (active) palette.text else palette.text, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Millio Creations", modifier = Modifier.padding(start = 10.dp, bottom = 4.dp), color = palette.secondaryText, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun CalculatorScreen(
    display: String, expression: String, palette: CalculatorPalette, large: Boolean, maxHeight: androidx.compose.ui.unit.Dp,
    pressedKey: String?, selectedOperationKey: String?, degrees: Boolean, inverse: Boolean, hasMemory: Boolean,
    onMenu: () -> Unit, onUndo: () -> Unit, onKey: (String) -> Unit, onMemory: (String) -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = if (large) 12.dp else 5.dp, vertical = if (maxHeight < 420.dp) 2.dp else 6.dp),
        verticalArrangement = Arrangement.spacedBy(if (maxHeight < 420.dp) 1.dp else 3.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(if (maxHeight < 420.dp) 30.dp else 38.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("☰", Modifier.clickable(onClick = onMenu).padding(horizontal = 10.dp), color = palette.text, fontSize = 23.sp)
            Text("MiCs Calculator", color = palette.secondaryText, fontSize = if (large) 16.sp else 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("↶", Modifier.clickable(onClick = onUndo).padding(horizontal = 12.dp), color = palette.secondaryText, fontSize = 25.sp)
        }
        Column(Modifier.fillMaxWidth().weight(1.55f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.End) {
            Text(expression, Modifier.fillMaxWidth(), fontSize = if (large) 22.sp else 16.sp, fontWeight = FontWeight.Bold, color = palette.secondaryText, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(display, Modifier.fillMaxWidth(), fontSize = when { large -> 66.sp; maxHeight < 420.dp -> 46.sp; else -> 58.sp }, fontWeight = FontWeight.Medium, color = palette.text, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().height(if (maxHeight < 420.dp) 23.dp else 30.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            listOf("MC", "MR", "M+", "M−", "MS").forEach { key ->
                Text(key, Modifier.weight(1f).wrapContentWidth(Alignment.CenterHorizontally).clickable { onMemory(key) }, color = if (key == "MR" && hasMemory) palette.accent else palette.secondaryText, fontSize = if (large) 15.sp else 12.sp, fontWeight = if (key == "MR" && hasMemory) FontWeight.Bold else FontWeight.Normal)
            }
        }
        val rows = listOf(
            listOf("%", "CE", "C", "⌫"),
            listOf("1/x", "x²", "√", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("±", "0", ".", "=")
        )
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(0.68f), horizontalArrangement = Arrangement.spacedBy(if (large) 3.dp else 2.dp), verticalAlignment = Alignment.CenterVertically) {
                row.forEach { key ->
                    val shown = when (key) {
                        "Deg/Rad" -> if (degrees) "Deg" else "Rad"
                        "√" -> "²√x".takeIf { inverse } ?: "√x"
                        else -> key
                    }
                    val isNumber = key in listOf("0","1","2","3","4","5","6","7","8","9",".")
                    val isOperator = key in listOf("÷","×","−","+")
                    val isScientific = !isNumber && !isOperator && key != "=" && key !in listOf("C", "CE", "⌫")
                    val active = pressedKey == key || (key == "Deg/Rad" && !degrees) || (key == "Inv" && inverse) || selectedOperationKey == key
                    val keyColor = when {
                        key == "=" -> palette.accent
                        active -> palette.selected
                        key in listOf("C", "CE", "⌫") -> palette.operator
                        isNumber -> palette.number
                        isOperator -> palette.operator
                        isScientific -> palette.scientific
                        else -> palette.operator
                    }
                    val keyText = if (key == "=" && pressedKey == "=") Color.White else palette.text
                    Button(
                        onClick = { onKey(key) },
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 1.dp),
                        shape = RoundedCornerShape(if (large) 12.dp else 10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = keyColor, contentColor = if (key == "=") Color.White else keyText),
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
                    ) {
                        Text(shown, fontSize = when { isNumber -> if (large) 36.sp else 32.sp; isOperator || key == "=" -> if (large) 32.sp else 29.sp; else -> if (large) 26.sp else 23.sp }, fontWeight = FontWeight.Normal, maxLines = 1, textAlign = TextAlign.Center, overflow = TextOverflow.Clip)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeScreen(
    palette: CalculatorPalette, colors: Map<String, Color>, selected: String, expanded: Boolean,
    onExpanded: (Boolean) -> Unit, onChoose: (String) -> Unit, onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Header(palette, "Tema de color", onBack)
        Text("Color principal", color = palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Box {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(palette.surface).clickable { onExpanded(true) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(colors[selected] ?: palette.accent))
                Text(selected, Modifier.weight(1f), color = palette.text, fontSize = 16.sp)
                Text("⌄", color = palette.text, fontSize = 22.sp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { onExpanded(false) }) {
                colors.forEach { (name, color) ->
                    DropdownMenuItem(
                        text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(color))
                            Text(name, fontWeight = if (name == selected) FontWeight.Bold else FontWeight.Normal)
                            if (name == selected) Text("✓", color = color)
                        } },
                        onClick = { onChoose(name) }
                    )
                }
            }
        }
        Text("Vista previa", color = palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.background).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("96 + 9 =", Modifier.fillMaxWidth(), color = palette.secondaryText, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
            Text("105", Modifier.fillMaxWidth(), color = palette.text, fontSize = 30.sp, textAlign = TextAlign.End)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Números" to palette.number, "Operadores" to palette.operator, "Científicas" to palette.scientific, "=" to palette.accent).forEach { (label, color) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).background(color), contentAlignment = Alignment.Center) { Text(if (label == "=") "=" else "8", color = if (label == "=") Color.White else palette.text, fontWeight = FontWeight.SemiBold) }
                        Text(label, color = palette.secondaryText, fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
        }
        Text("Los tonos se adaptan automáticamente al modo claro u oscuro.", color = palette.secondaryText, fontSize = 14.sp)
    }
}

@Composable
private fun SettingsScreen(palette: CalculatorPalette, appearance: String, degrees: Boolean, onAppearance: (String) -> Unit, onDegrees: (Boolean) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Header(palette, "Configuración", onBack)
        Text("Apariencia", color = palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(palette.surface).padding(12.dp)) {
            Text("Tema de la aplicación", color = palette.text, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Text("Selecciona cómo se muestra la aplicación", color = palette.secondaryText, fontSize = 13.sp)
            listOf("light" to "Claro", "dark" to "Oscuro", "system" to "Usar la configuración del sistema").forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().clickable { onAppearance(value) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = appearance == value, onClick = { onAppearance(value) }, colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = palette.accent))
                    Text(label, color = palette.text, fontSize = 15.sp)
                }
            }
        }
        Text("Preferencias de cálculo", color = palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(palette.surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Unidad angular", color = palette.text, fontWeight = FontWeight.Medium)
            Text("Se usa en las funciones seno, coseno y tangente.", color = palette.secondaryText, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(true to "Grados (Deg)", false to "Radianes (Rad)").forEach { (value, label) ->
                    val active = degrees == value
                    Text(label, Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (active) palette.selected else palette.background).clickable { onDegrees(value) }.padding(vertical = 12.dp, horizontal = 6.dp), color = if (active) palette.accent else palette.text, textAlign = TextAlign.Center, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun InfoScreen(palette: CalculatorPalette, title: String, heading: String, version: String, description: String, onBack: () -> Unit, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Header(palette, title, onBack)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(palette.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(heading, color = palette.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            if (version.isNotBlank()) Text("Versión $version", color = palette.secondaryText, fontSize = 14.sp)
            Text(description, color = palette.secondaryText, fontSize = 14.sp)
        }
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = palette.accent), modifier = Modifier.fillMaxWidth()) { Text(actionLabel, color = Color.White) }
        }
    }
}

@Composable
private fun Header(palette: CalculatorPalette, title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("‹", Modifier.clickable(onClick = onBack).padding(horizontal = 4.dp), color = palette.text, fontSize = 32.sp)
        Text(title, color = palette.text, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun appVersion(context: android.content.Context): String = try {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
} catch (_: Exception) { "1.0" }

private fun createPalette(accent: Color, dark: Boolean): CalculatorPalette {
    val bg = if (dark) Color(0xFF17181B) else Color(0xFFFDFDFD)
    val surface = if (dark) Color(0xFF24262B) else Color(0xFFF4F5F7)
    val text = if (dark) Color(0xFFF4F5F7) else Color(0xFF202124)
    val secondary = if (dark) Color(0xFFB9BDC6) else Color(0xFF666B73)
    val number = mix(accent, bg, if (dark) 0.84f else 0.91f)
    val operator = mix(accent, bg, if (dark) 0.59f else 0.57f)
    val scientific = mix(accent, bg, if (dark) 0.43f else 0.39f)
    val active = mix(accent, bg, if (dark) 0.34f else 0.22f)
    val finalAccent = if (dark) mix(accent, Color.White, 0.18f) else accent
    return CalculatorPalette(bg, surface, text, secondary, number, operator, scientific, finalAccent, active, if (dark) Color(0xFF3A3D44) else Color(0xFFE2E4E8))
}

private fun mix(foreground: Color, background: Color, amount: Float): Color = Color(
    red = foreground.red * (1f - amount) + background.red * amount,
    green = foreground.green * (1f - amount) + background.green * amount,
    blue = foreground.blue * (1f - amount) + background.blue * amount,
    alpha = 1f
)

