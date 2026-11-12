package com.stash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What Stash keeps about add-ons stays on this phone (add-ons phase 4 §2.10, review-885 N6): the
 * user's own add-ons (links and tokens, sealed with this phone's keys), the manifests kept from
 * them, and the kept copy of the signed list. A phone-to-phone transfer that carried the kept
 * manifests without the entries they belong to left files nothing would ever clean up.
 *
 * Reads the rules file itself: it is what Android reads, and nothing in the app's code can stand
 * in for it.
 */
class AddonStoresStayOnThisPhoneTest {

    private val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()

    private fun section(name: String): String = rules.substringAfter("<$name>").substringBefore("</$name>")

    @Test fun `no add-on store is in a cloud backup or a phone-to-phone transfer`() {
        for (part in listOf("cloud-backup", "device-transfer")) {
            for (store in listOf("user_addons", "addon_manifests", "addon_index")) {
                val line = """<exclude domain="file" path="datastore/$store.preferences_pb" />"""
                assertTrue("$store is left out of $part", line in section(part))
            }
        }
    }
}
