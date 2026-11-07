package io.github.ashrodan.herdrmonitor.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ashrodan.herdrmonitor.R

// The Herdr brand art (the border-collie pack): the collie is the
// orchestrator, the sheep are the agents. Only the pieces the app uses are
// in res/; the palette is res/values/herdr_colors.xml.

// One-colour 24dp glyphs, tinted like Material icons.
enum class HerdrGlyph(@DrawableRes val res: Int) {
    Sheep(R.drawable.ic_sheep),
    Paddock(R.drawable.ic_paddock),
}

@Composable
fun HerdrIcon(glyph: HerdrGlyph, description: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    Icon(painterResource(glyph.res), description, modifier, tint)

// Full-colour scenes for the empty and offline states.
enum class HerdrScene(@DrawableRes val res: Int, val description: String) {
    AllPenned(R.drawable.illo_all_penned, "Sheep and collie asleep by a closed pen"),
    Offline(R.drawable.illo_offline, "An empty paddock at night, the collie by a shut gate"),
}

// A scene with one line under it. The scene is decoration: the text says
// what the state is, so a screen reader reads only the text.
@Composable
fun SceneState(scene: HerdrScene, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(scene.res), null, Modifier.widthIn(max = 360.dp).fillMaxWidth(), contentScale = ContentScale.Fit)
        Text(text, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
