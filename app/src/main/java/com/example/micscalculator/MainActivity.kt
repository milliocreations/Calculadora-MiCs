package com.milliocreations.micscalculator

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.content.edit
import androidx.core.net.toUri
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.*
import kotlin.time.Duration.Companion.milliseconds

private val PlusJakartaSans = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold)
)

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
    val parenthesisMode: Boolean,
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

private class CalculatorExpressionParser(private val source: String) {
    private var position = 0

    fun parse(): Double {
        val result = parseSum()
        skipSpaces()
        require(position == source.length) { "Expresión incompleta" }
        return result
    }

    private fun parseSum(): Double {
        var value = parseProduct()
        while (true) {
            skipSpaces()
            value = when {
                take('+') -> value + parseProduct()
                take('-') || take('−') -> value - parseProduct()
                else -> return value
            }
        }
    }

    private fun parseProduct(): Double {
        var value = parsePower()
        while (true) {
            skipSpaces()
            value = when {
                take('×') || take('*') -> value * parsePower()
                take('÷') || take('/') -> {
                    val divisor = parsePower()
                    if (divisor == 0.0) Double.NaN else value / divisor
                }
                source.startsWith("mod", position, ignoreCase = true) -> {
                    position += 3
                    val divisor = parsePower()
                    if (divisor == 0.0) Double.NaN else value % divisor
                }
                else -> return value
            }
        }
    }

    private fun parsePower(): Double {
        val base = parseUnary()
        skipSpaces()
        return if (take('^')) base.pow(parsePower()) else base
    }

    private fun parseUnary(): Double {
        skipSpaces()
        return when {
            take('+') -> parseUnary()
            take('-') || take('−') -> -parseUnary()
            take('(') -> {
                val value = parseSum()
                skipSpaces()
                require(take(')')) { "Falta cerrar un paréntesis" }
                value
            }
            else -> parseNumber()
        }
    }

    private fun parseNumber(): Double {
        skipSpaces()
        val start = position
        var decimalSeen = false
        while (position < source.length) {
            val char = source[position]
            when {
                char.isDigit() -> position++
                (char == ',' || char == '.') && !decimalSeen -> { decimalSeen = true; position++ }
                else -> break
            }
        }
        require(position > start) { "Falta un número" }
        return source.substring(start, position).replace(',', '.').toDouble()
    }

    private fun take(char: Char): Boolean {
        if (position < source.length && source[position] == char) {
            position++
            return true
        }
        return false
    }

    private fun skipSpaces() {
        while (position < source.length && source[position].isWhitespace()) position++
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
    var parenthesisMode by remember { mutableStateOf(false) }
    var scientificNotation by remember { mutableStateOf(false) }
    var memoryValue by remember { mutableDoubleStateOf(0.0) }
    var hasMemory by remember { mutableStateOf(false) }
    var pressedKey by remember { mutableStateOf<String?>(null) }
    var selectedOperationKey by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf("calculator") }
    var menuOpen by remember { mutableStateOf(false) }
    var appearance by remember { mutableStateOf(prefs.getString("appearance", "system") ?: "system") }
    // New preference key: an older saved scientific_mode=true must not force scientific mode at startup.
    var scientificMode by remember { mutableStateOf(prefs.getBoolean("calculator_scientific_mode", false)) }
    val history = remember { mutableStateListOf<String>() }
    val undoStack = remember { mutableStateListOf<CalcSnapshot>() }

    // El botón Atrás del sistema cierra primero el menú y, desde otras pantallas,
    // vuelve a la calculadora en lugar de dejar la pantalla sin respuesta.
    BackHandler(enabled = menuOpen || screen != "calculator") {
        if (menuOpen) menuOpen = false else screen = "calculator"
    }

    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = when (appearance) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
    val palette = remember(dark) { createPalette(Color(0xFF356FF0), dark) }

    LaunchedEffect(pressedKey) {
        if (pressedKey != null) {
            delay(150.milliseconds)
            pressedKey = null
        }
    }

    fun formatNumber(number: Double): String {
        if (number.isNaN() || number.isInfinite()) return "Error"
        return if (number % 1.0 == 0.0 && abs(number) < 1.0e15) {
            number.toLong().toString()
        } else {
            String.format(Locale.US, "%.10f", number).trimEnd('0').trimEnd('.').replace('.', ',')
        }
    }

    fun currentNumber(): Double = display.replace(',', '.').toDoubleOrNull() ?: 0.0

    fun snapshot() = CalcSnapshot(display, expression, previousValue, operation, newNumber,
        degrees, inverse, expMode, expBase, parenthesisMode, memoryValue, hasMemory)

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
        if (parenthesisMode) {
            val result = runCatching { CalculatorExpressionParser(expression).parse() }.getOrElse { Double.NaN }
            expression = "$expression ="
            display = formatNumber(result)
            history.add(0, "$expression $display")
            parenthesisMode = false
            previousValue = null
            operation = null
            newNumber = true
            selectedOperationKey = null
            return
        }
        if (expMode) {
            val exponent = currentNumber()
            val base = expBase ?: 0.0
            display = formatNumber(base * 10.0.pow(exponent))
            expression = "${formatNumber(base)} \u00D7 10^${formatNumber(exponent)} ="
            history.add(0, "$expression $display")
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
            "\u00D7" -> first * second
            "\u00F7" -> if (second != 0.0) first / second else Double.NaN
            "mod" -> if (second != 0.0) first % second else Double.NaN
            "^" -> first.pow(second)
            else -> second
        }
        val symbol = if (op == "-") "−" else op.orEmpty()
        expression = "${formatNumber(first)} $symbol ${formatNumber(second)} ="
        display = formatNumber(result)
        history.add(0, "$expression $display")
        previousValue = null
        operation = null
        newNumber = true
        selectedOperationKey = null
    }

    fun pressNumber(number: String) {
        if (parenthesisMode) {
            if (newNumber || display == "0" || display == "Error" || display == "(") {
                if (expression.lastOrNull()?.let { it.isDigit() || it == ',' || it == ')' } == true) expression += "×"
                display = number
                expression += number
                newNumber = false
            } else {
                display += number
                expression += number
            }
            return
        }
        if (newNumber || display == "0" || display == "Error") {
            if (operation == null && !expMode) expression = ""
            display = number
            newNumber = false
        } else {
            display += number
        }
    }

    fun pressDecimal() {
        if (parenthesisMode) {
            if (newNumber || display == "0" || display == "(" || display == "Error") {
                if (expression.lastOrNull()?.let { it.isDigit() || it == ',' || it == ')' } == true) expression += "×"
                display = "0,"
                expression += "0,"
                newNumber = false
            } else if (!display.contains(',')) {
                display += ","
                expression += ","
            }
            return
        }
        if (newNumber || display == "Error") {
            if (operation == null && !expMode) expression = ""
            display = "0,"
            newNumber = false
        } else if (!display.contains(',')) display += ","
    }

    fun pressBackspace() {
        if (parenthesisMode && expression.isNotEmpty()) {
            expression = expression.dropLast(1)
            val token = expression.takeLastWhile { it.isDigit() || it == ',' }
            display = token.ifEmpty { if (expression.endsWith("(")) "(" else "0" }
            newNumber = token.isEmpty()
            return
        }
        if (display == "Error" || display.length <= 1 || (display.length == 2 && display.startsWith("-"))) {
            display = "0"
        } else display = display.dropLast(1)
        newNumber = false
    }

    fun pressOperation(op: String) {
        if (parenthesisMode) {
            if (expression.lastOrNull()?.let { it.isDigit() || it == ',' || it == ')' } == true || (op == "-" && expression.endsWith("("))) {
                expression += when (op) { "-" -> "−"; else -> op }
                display = "0"
                operation = null
                previousValue = null
                selectedOperationKey = when (op) { "-" -> "−"; "^" -> "xʸ"; else -> op }
                newNumber = true
            }
            return
        }
        if (previousValue != null && !newNumber) calculate()
        previousValue = currentNumber()
        operation = op
        val shown = if (op == "-") "−" else if (op == "^") "^" else op
        expression = "${formatNumber(previousValue ?: 0.0)} $shown"
        selectedOperationKey = when (op) { "-" -> "−"; "^" -> "xʸ"; else -> op }
        newNumber = true
    }

    fun pressParenthesis(opening: Boolean) {
        if (opening) {
            if (!parenthesisMode) {
                val prefix = when {
                    operation != null -> "${expression.trimEnd()} "
                    !newNumber && display != "Error" -> "${display}×"
                    else -> ""
                }
                expression = "$prefix("
                parenthesisMode = true
                previousValue = null
                operation = null
                selectedOperationKey = null
            } else {
                if (expression.lastOrNull()?.let { it.isDigit() || it == ',' || it == ')' } == true) expression += "×"
                expression += "("
            }
            display = "("
            newNumber = true
            return
        }

        if (!parenthesisMode) return
        val opens = expression.count { it == '(' }
        val closes = expression.count { it == ')' }
        val last = expression.lastOrNull()
        if (opens > closes && last != null && last != '(' && last !in listOf('+', '-', '−', '×', '÷', '^', ',')) {
            expression += ")"
            if (expression.count { it == '(' } == expression.count { it == ')' }) {
                runCatching { CalculatorExpressionParser(expression).parse() }
                    .onSuccess { display = formatNumber(it) }
            }
            newNumber = true
        }
    }

    fun pressExp() {
        expBase = currentNumber()
        expMode = true
        expression = "${formatNumber(expBase ?: 0.0)} \u00D7 10^"
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
            "√" -> if (wasInverse) number * number else sqrt(number)
            "x!" -> if (number < 0 || number > 170 || number % 1.0 != 0.0) Double.NaN else { var f = 1.0; for (i in 1..number.toInt()) f *= i; f }
            "abs" -> abs(number)
            "10ˣ" -> 10.0.pow(number)
            "π" -> Math.PI
            "e" -> Math.E
            "%" -> number / 100.0
            "±" -> -number
            else -> number
        }
        val label = when (type) {
            "sin" -> if (wasInverse) "sin\u207B\u00B9" else "sin"
            "cos" -> if (wasInverse) "cos\u207B\u00B9" else "cos"
            "tan" -> if (wasInverse) "tan\u207B\u00B9" else "tan"
            "ln" -> if (wasInverse) "e^" else "ln"
            "log" -> if (wasInverse) "10^" else "log"
            else -> type
        }
        expression = "$label(${formatNumber(number)}) ="
        display = formatNumber(result)
        history.add(0, "$expression $display")
        newNumber = true
        if (type in listOf("sin", "cos", "tan", "ln", "log")) inverse = false
    }

    fun doUndo() {
        if (undoStack.isNotEmpty()) {
            val s = undoStack.removeAt(undoStack.lastIndex)
            display = s.display; expression = s.expression; previousValue = s.previousValue
            operation = s.operation; newNumber = s.newNumber; degrees = s.degrees
            inverse = s.inverse; expMode = s.expMode; expBase = s.expBase
            parenthesisMode = s.parenthesisMode
            memoryValue = s.memoryValue; hasMemory = s.hasMemory
            selectedOperationKey = operation?.let { when (it) { "-" -> "−"; "^" -> "xʸ"; else -> it } }
        }
    }

    fun chooseAppearance(value: String) {
        appearance = value
        prefs.edit { putString("appearance", value) }
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
            context.startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=${context.packageName}".toUri()))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=${context.packageName}".toUri()))
        }
    }

    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.merge(TextStyle(fontFamily = PlusJakartaSans))) {
        Surface(modifier = Modifier.fillMaxSize(), color = palette.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val large = maxWidth >= 600.dp
                val sideWidth = maxWidth * if (large) 0.48f else 0.78f

                when (screen) {
                    "calculator" -> CalculatorScreen(
                        display = display, expression = expression, palette = palette, large = large,
                        maxHeight = maxHeight, pressedKey = pressedKey, selectedOperationKey = selectedOperationKey,
                        degrees = degrees, inverse = inverse, hasMemory = hasMemory, scientificMode = scientificMode,
                        scientificNotation = scientificNotation,
                        onHistory = { screen = "history" },
                        // No llames a tap() aquí: tap guarda una instantánea nueva y
                        // doUndo terminaría retirando esa misma instantánea sin volver atrás.
                        onMenu = { menuOpen = true }, onUndo = { pressedKey = "undo"; doUndo() },
                        onKey = { key -> tap(key) {
                            when (key) {
                                "0", "1", "2", "3", "4", "5", "6", "7", "8", "9" -> pressNumber(key)
                                "." -> pressDecimal()
                                "⌫" -> pressBackspace()
                                "CE" -> {
                                    if (parenthesisMode) expression = expression.dropLastWhile { it.isDigit() || it == ',' }
                                    display = "0"; newNumber = true
                                }
                                "C" -> { display = "0"; expression = ""; previousValue = null; operation = null; newNumber = true; inverse = false; expMode = false; expBase = null; parenthesisMode = false; selectedOperationKey = null }
                                "1/x" -> { val n = currentNumber(); expression = "1/(${formatNumber(n)}) ="; display = formatNumber(if (n != 0.0) 1.0 / n else Double.NaN); newNumber = true }
                                "x²" -> { val n = currentNumber(); expression = "(${formatNumber(n)})² ="; display = formatNumber(n * n); newNumber = true }
                                "Deg/Rad" -> { degrees = !degrees; prefs.edit { putBoolean("degrees", degrees) } }
                                "Inv" -> inverse = !inverse
                                "sin", "cos", "tan", "ln", "log", "\u221A", "x!", "π", "e", "%", "±", "abs", "10ˣ" -> scientific(key)
                                "EXP" -> pressExp()
                                "mod" -> pressOperation("mod")
                                "F-E" -> scientificNotation = !scientificNotation
                                "xʸ" -> pressOperation("^")
                                "÷" -> pressOperation("÷")
                                "×" -> pressOperation("×")
                                "−" -> pressOperation("-")
                                "+" -> pressOperation("+")
                                "=" -> calculate()
                                "(" -> pressParenthesis(opening = true)
                                ")" -> pressParenthesis(opening = false)
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
                    "settings" -> SettingsScreen(
                        palette = palette, appearance = appearance, version = appVersion(context),
                        onAppearance = ::chooseAppearance,
                        onFeedback = ::sendFeedback,
                        onRate = ::rateApp,
                        onBack = { screen = "calculator" }
                    )
                    "history" -> HistoryScreen(palette, history, { screen = "calculator" }, { history.clear() })
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
                        Text("Calculadora", modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 2.dp), color = palette.secondaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        val items = listOf(
                            Triple("mode_basic", "▦", if (scientificMode) "Cambiar a estándar" else "Estándar"),
                            Triple("mode_scientific", "⚗", if (scientificMode) "Científica" else "Cambiar a científica"),
                            Triple("history", "\u25F7", "Historial")
                        )
                        items.forEach { (id, glyph, label) ->
                            val active = when (id) {
                                "mode_basic" -> !scientificMode && screen == "calculator"
                                "mode_scientific" -> scientificMode && screen == "calculator"
                                else -> screen == id
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                                    .background(if (active) palette.selected else Color.Transparent)
                                    .clickable {
                                        when (id) {
                                            "mode_basic" -> { scientificMode = false; prefs.edit { putBoolean("calculator_scientific_mode", false) }; screen = "calculator" }
                                            "mode_scientific" -> { scientificMode = true; prefs.edit { putBoolean("calculator_scientific_mode", true) }; screen = "calculator" }
                                            else -> screen = id
                                        }
                                        menuOpen = false
                                    }
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
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .clickable { screen = "settings"; menuOpen = false }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("⚙", color = palette.text, fontSize = 19.sp)
                            Text("Configuración", color = palette.text, fontSize = 14.sp)
                        }
                        Text("Millio Creations", modifier = Modifier.padding(start = 10.dp, bottom = 4.dp), color = palette.secondaryText, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CalculatorScreen(
    display: String, expression: String, palette: CalculatorPalette, large: Boolean, maxHeight: androidx.compose.ui.unit.Dp,
    pressedKey: String?, selectedOperationKey: String?, degrees: Boolean, inverse: Boolean, hasMemory: Boolean,
    scientificMode: Boolean, scientificNotation: Boolean, onHistory: () -> Unit,
    onMenu: () -> Unit, onUndo: () -> Unit, onKey: (String) -> Unit, onMemory: (String) -> Unit
) {
    var trigMenuOpen by remember { mutableStateOf(false) }
    var functionMenuOpen by remember { mutableStateOf(false) }
    val verticalPadding = if (maxHeight < 420.dp) 2.dp else 6.dp
    val itemSpacing = if (maxHeight < 420.dp) 2.dp else 4.dp
    val headerHeight = if (maxHeight < 420.dp) 30.dp else 38.dp
    val memoryHeight = if (maxHeight < 420.dp) 23.dp else 30.dp
    val standardWeightedHeight = (maxHeight - verticalPadding * 2 - headerHeight - memoryHeight - itemSpacing * 7).coerceAtLeast(0.dp)
    val standardDisplayHeight = standardWeightedHeight * (1.55f / 4.95f)
    val scientificKeyHeight = ((maxHeight - verticalPadding * 2 - headerHeight - memoryHeight - itemSpacing * 11 - 22.dp - 36.dp - standardDisplayHeight) / 7f).coerceAtLeast(0.dp)
    Column(
        Modifier.fillMaxSize().padding(horizontal = if (large) 12.dp else 5.dp, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(itemSpacing)
    ) {
        Row(Modifier.fillMaxWidth().height(headerHeight), verticalAlignment = Alignment.CenterVertically) {
            Text("☰", Modifier.clickable(onClick = onMenu).padding(horizontal = 10.dp), color = palette.text, fontSize = 23.sp)
            Text(if (scientificMode) "Científica" else "Estándar", color = palette.secondaryText, fontSize = if (large) 16.sp else 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("\u25F7", Modifier.clickable(onClick = onHistory).padding(horizontal = 8.dp), color = palette.secondaryText, fontSize = 21.sp)
            Text("↶", Modifier.clickable(onClick = onUndo).padding(horizontal = 12.dp), color = palette.secondaryText, fontSize = 25.sp)
        }
        val displayModifier = if (scientificMode) Modifier.fillMaxWidth().height(standardDisplayHeight) else Modifier.fillMaxWidth().weight(1.55f)
        Column(displayModifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.End) {
            Text(expression, Modifier.fillMaxWidth(), fontSize = if (large) 24.sp else 18.sp, fontWeight = FontWeight.Normal, color = Color(0xFF969696), textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val shownDisplay = if (scientificNotation) {
                display.replace(',', '.').toDoubleOrNull()?.let { String.format(Locale.US, "%.6E", it).replace('.', ',') } ?: display
            } else display
            Text(shownDisplay, Modifier.fillMaxWidth(), fontSize = when { large -> 66.sp; maxHeight < 420.dp -> 46.sp; else -> 58.sp }, fontWeight = FontWeight.Bold, color = palette.text, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (scientificMode) {
            Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (degrees) "DEG" else "RAD", Modifier.clickable { onKey("Deg/Rad") }.padding(horizontal = 10.dp), color = palette.secondaryText, fontSize = 14.sp)
                Text("F-E", Modifier.clickable { onKey("F-E") }.padding(horizontal = 10.dp), color = if (scientificNotation) palette.accent else palette.secondaryText, fontSize = 14.sp)
            }
        }
        Row(Modifier.fillMaxWidth().height(memoryHeight), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            listOf("MC", "MR", "M+", "M−", "MS").forEach { key ->
                Text(key, Modifier.weight(1f).wrapContentWidth(Alignment.CenterHorizontally).clickable { onMemory(key) }, color = if (key == "MR" && hasMemory) palette.accent else palette.secondaryText, fontSize = if (large) 17.sp else 14.sp, fontWeight = FontWeight.Normal)
            }
        }
        if (scientificMode) {
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Text("◿  Trigonometría  ⌄", Modifier.clickable {
                        trigMenuOpen = true
                        functionMenuOpen = false
                    }.padding(horizontal = 8.dp, vertical = 6.dp), color = palette.text, fontSize = 14.sp)
                    DropdownMenu(expanded = trigMenuOpen, onDismissRequest = { trigMenuOpen = false }) {
                        listOf("sin", "cos", "tan").forEach { key ->
                            DropdownMenuItem(text = { Text(key) }, onClick = { trigMenuOpen = false; onKey(key) })
                        }
                    }
                }
                Box {
                    Text("ƒ  Función  ⌄", Modifier.clickable {
                        functionMenuOpen = true
                        trigMenuOpen = false
                    }.padding(horizontal = 8.dp, vertical = 6.dp), color = palette.text, fontSize = 14.sp)
                    DropdownMenu(expanded = functionMenuOpen, onDismissRequest = { functionMenuOpen = false }) {
                        listOf("log", "ln", "abs", "10ˣ", "x!", "√", "EXP", "mod").forEach { key ->
                            val label = when (key) { "x!" -> "n!"; "EXP" -> "exp"; "√" -> "√x"; else -> key }
                            DropdownMenuItem(text = { Text(label) }, onClick = { functionMenuOpen = false; onKey(key) })
                        }
                    }
                }
            }
        }
        val rows = if (scientificMode) listOf(
            listOf("Inv", "π", "e", "C", "⌫"),
            listOf("x²", "1/x", "abs", "EXP", "mod"),
            listOf("√", "(", ")", "x!", "÷"),
            listOf("xʸ", "7", "8", "9", "×"),
            listOf("10ˣ", "4", "5", "6", "−"),
            listOf("log", "1", "2", "3", "+"),
            listOf("ln", "±", "0", ".", "=")
        ) else listOf(
            listOf("%", "CE", "C", "⌫"),
            listOf("7", "8", "9", "÷"),
            listOf("4", "5", "6", "×"),
            listOf("1", "2", "3", "−"),
            listOf("±", "0", ".", "=")
        )
        rows.forEach { row ->
            val rowModifier = if (scientificMode) Modifier.height(scientificKeyHeight) else Modifier.weight(0.68f)
            Row(Modifier.fillMaxWidth().then(rowModifier), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                row.forEach { key ->
                    val shown = when (key) {
                        "Inv" -> "2nd"
                        "EXP" -> "exp"
                        "x!" -> "n!"
                        "√" -> "²√x"
                        "abs" -> "|x|"
                        "." -> ","
                        else -> key
                    }
                    val isNumber = key in listOf("0","1","2","3","4","5","6","7","8","9",".")
                    val isDigit = key in listOf("0","1","2","3","4","5","6","7","8","9")
                    val isOperator = key in listOf("÷","×","−","+","mod")
                    val isScientific = !isNumber && !isOperator && key != "=" && key !in listOf("C", "CE", "⌫")
                    val active = pressedKey == key || (key == "Inv" && inverse) || selectedOperationKey == key
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
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = keyColor, contentColor = if (key == "=") Color.White else keyText),
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
                    ) {
                        val keyFontSize = if (scientificMode) {
                            when {
                                isDigit -> if (large) 32.sp else 28.sp
                                key in listOf("Inv", "x²", "√", "xʸ", "10ˣ", "log", "ln", "1/x", "abs", "EXP", "mod") -> if (large) 22.sp else 19.sp
                                else -> if (large) 23.sp else 20.sp
                            }
                        } else {
                            when { isNumber -> if (large) 34.sp else 30.sp; isOperator || key == "=" -> if (large) 30.sp else 27.sp; else -> if (large) 24.sp else 21.sp }
                        }
                        Text(shown, fontSize = keyFontSize, fontWeight = FontWeight.Normal, maxLines = 1, textAlign = TextAlign.Center, overflow = TextOverflow.Clip)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(palette: CalculatorPalette, history: List<String>, onBack: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Header(palette, "Historial", onBack)
        if (history.isEmpty()) {
            Text("Todavía no hay operaciones.", color = palette.secondaryText, fontSize = 15.sp)
        } else {
            history.forEach { entry ->
                Text(entry, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(palette.surface).padding(14.dp), color = palette.text, fontSize = 16.sp)
            }
            Text("Borrar historial", Modifier.clickable(onClick = onClear).padding(12.dp), color = palette.accent, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SettingsScreen(
    palette: CalculatorPalette,
    appearance: String,
    version: String,
    onAppearance: (String) -> Unit,
    onFeedback: () -> Unit,
    onRate: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var themeMenuOpen by remember { mutableStateOf(false) }
    var customizeExpanded by remember { mutableStateOf(false) }
    var aboutExpanded by remember { mutableStateOf(false) }
    val privacyPolicyUrl = "https://sites.google.com/view/mics-calculator-legal/pol%C3%ADtica-de-privacidad"
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Header(palette, "Configuración", onBack)
        Text("Apariencia", color = palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(palette.surface)) {
            Row(
                Modifier.fillMaxWidth().clickable { themeMenuOpen = !themeMenuOpen }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Tema de la aplicación", color = palette.text, fontSize = 16.sp)
                    Text("Seleccionar el tema de la aplicación", color = palette.secondaryText, fontSize = 14.sp, lineHeight = 18.sp)
                    Text("que se va a mostrar", color = palette.secondaryText, fontSize = 14.sp, lineHeight = 18.sp)
                }
                    FlatChevron(expanded = themeMenuOpen, color = palette.text)
            }
            if (themeMenuOpen) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
                listOf(
                    "light" to "Claro",
                    "dark" to "Oscuro",
                    "system" to "Usar la configuración del sistema"
                ).forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onAppearance(value) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = appearance == value,
                            onClick = { onAppearance(value) },
                            colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = palette.accent)
                        )
                    Text(label, color = palette.text, fontSize = 16.sp, lineHeight = 20.sp)
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(palette.surface)) {
            Row(
                Modifier.fillMaxWidth().clickable { customizeExpanded = !customizeExpanded }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Personalizar calculadora", Modifier.weight(1f), color = palette.text, fontSize = 16.sp)
                FlatChevron(expanded = customizeExpanded, color = palette.text)
            }
            if (customizeExpanded) {
                Text("En construcción", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), color = palette.secondaryText, fontSize = 14.sp)
            }
        }

        Text("Acerca de", color = palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(palette.surface)) {
            Row(
                Modifier.fillMaxWidth().clickable { aboutExpanded = !aboutExpanded }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("MiCs Calculator", color = palette.text, fontSize = 16.sp)
                    Text("Versión $version", color = palette.secondaryText, fontSize = 14.sp)
                }
                FlatChevron(expanded = aboutExpanded, color = palette.text)
            }
            if (aboutExpanded) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
                Text(
                    "Política de privacidad",
                    Modifier.fillMaxWidth().clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, privacyPolicyUrl.toUri()))
                    }.padding(16.dp),
                    color = palette.accent, fontSize = 14.sp
                )
            }
        }

        Text(
            "Enviar comentarios",
            Modifier.clickable(onClick = onFeedback).padding(vertical = 4.dp),
            color = palette.accent, fontSize = 16.sp
        )
        Text(
            "Tus comentarios importan: califica la aplicación en Google Play Store",
            Modifier.fillMaxWidth().clickable(onClick = onRate).padding(vertical = 4.dp),
            color = palette.accent, fontSize = 15.sp
        )
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
        androidx.compose.material3.IconButton(onClick = onBack) {
            Text("‹", color = palette.text, fontSize = 32.sp)
        }
        Text(title, color = palette.text, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FlatChevron(expanded: Boolean, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(width = 18.dp, height = 10.dp)) {
        val stroke = 1.6.dp.toPx()
        val middleX = size.width / 2f
        val edgeX = 1.dp.toPx()
        val highY = size.height * 0.30f
        val lowY = size.height * 0.70f
        val leftY = if (expanded) lowY else highY
        val rightY = leftY
        val middleY = if (expanded) highY else lowY
        drawLine(color, androidx.compose.ui.geometry.Offset(edgeX, leftY), androidx.compose.ui.geometry.Offset(middleX, middleY), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(middleX, middleY), androidx.compose.ui.geometry.Offset(size.width - edgeX, rightY), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

private fun appVersion(context: android.content.Context): String = try {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
} catch (_: Exception) { "1.0" }

private fun createPalette(accent: Color, dark: Boolean): CalculatorPalette {
    val bg = if (dark) Color(0xFF17181B) else Color(0xFFF7F3EB)
    val surface = if (dark) Color(0xFF24262B) else Color(0xFFFFFEFC)
    val text = if (dark) Color(0xFFF4F5F7) else Color(0xFF202124)
    val secondary = if (dark) Color(0xFFB9BDC6) else Color(0xFF66635F)
    val number = if (dark) mix(accent, bg, 0.84f) else Color(0xFFFFFEFC)
    val operator = if (dark) mix(accent, bg, 0.59f) else Color(0xFFF5F2EC)
    val scientific = if (dark) mix(accent, bg, 0.43f) else Color(0xFFF2EEE7)
    val active = if (dark) mix(accent, bg, 0.34f) else Color(0xFFFFE2C2)
    val finalAccent = if (dark) mix(accent, Color.White, 0.18f) else accent
    return CalculatorPalette(bg, surface, text, secondary, number, operator, scientific, finalAccent, active, if (dark) Color(0xFF3A3D44) else Color(0xFFE1DDD5))
}

private fun mix(foreground: Color, background: Color, amount: Float): Color = Color(
    red = foreground.red * (1f - amount) + background.red * amount,
    green = foreground.green * (1f - amount) + background.green * amount,
    blue = foreground.blue * (1f - amount) + background.blue * amount,
    alpha = 1f
)

