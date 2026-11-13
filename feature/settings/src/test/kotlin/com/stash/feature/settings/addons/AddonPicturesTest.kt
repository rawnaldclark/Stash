package com.stash.feature.settings.addons

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.stash.data.download.lossless.addon.AddonLinks
import com.stash.data.download.lossless.addon.AddonOverview
import org.junit.Test
import java.io.File

/**
 * An add-on's row, its page and its preview ask the image loader for nothing.
 *
 * A manifest can name an `icon`, an address its author chose. Fetching it would tell that server
 * (or another one) about the phone before the user has agreed to anything, and for an add-on Stash
 * doesn't run it would do so on every showing of Settings › Add-ons. So these screens draw a letter
 * ([AddonAvatar]) and load no picture at all. If one is ever drawn for an official add-on, it must
 * be the signed index's icon, never the manifest's, and never for an add-on Stash doesn't run:
 * whoever adds it changes this test knowingly.
 *
 * Held two ways: the screens' own code names no image loader, and nothing they are drawn from has
 * a field that could carry a picture's address.
 */
class AddonPicturesTest {

    /** The module's own directory is where its unit tests run. */
    private val screens = File("src/main/kotlin/com/stash/feature/settings/addons")

    @Test fun `the add-on screens call no image loader`() {
        val files = screens.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertThat(files.map { it.name }).containsAtLeast(
            "AddonsScreen.kt", "AddonPageScreen.kt", "AddFromLinkScreen.kt", "NotMadeByStashSheet.kt", "AddonText.kt",
        )
        val loaders = listOf("coil", "AsyncImage", "rememberAsyncImagePainter", "ImageRequest", "BitmapFactory", "painterResource", "ImageBitmap")
        for (file in files) {
            val code = file.readText()
            for (loader in loaders) {
                assertWithMessage("${file.name} names an image loader: $loader").that(code.contains(loader)).isFalse()
            }
        }
    }

    @Test fun `nothing a row, a page or a preview is drawn from can carry a picture's address`() {
        val pictureWords = listOf("icon", "image", "artwork", "logo", "avatar", "picture")
        for (model in listOf(AddonOverview.Row::class.java, AddonLinks.Preview::class.java)) {
            val fields = model.declaredFields.map { it.name }
            assertThat(fields).isNotEmpty()
            for (field in fields) for (word in pictureWords) {
                assertWithMessage("${model.simpleName}.$field").that(field.lowercase().contains(word)).isFalse()
            }
        }
        // The one address a preview holds is the user's own link, which is read once, on their tap, and never drawn.
        val addresses = AddonLinks.Preview::class.java.declaredFields.filter { it.type == String::class.java }.map { it.name }
        assertThat(addresses).containsExactly("manifestUrl", "id", "name", "version", "description", "host", "replacesKey")
    }
}
