// core/ui/src/main/kotlin/com/stash/core/ui/components/ArtistProfileSkeletons.kt
package com.stash.core.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ArtistHeroSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShimmerPlaceholder(Modifier.size(96.dp), shape = CircleShape)
        Spacer(Modifier.height(16.dp))
        ShimmerPlaceholder(
            Modifier.height(28.dp).width(180.dp),
            shape = RoundedCornerShape(6.dp),
        )
        Spacer(Modifier.height(8.dp))
        ShimmerPlaceholder(
            Modifier.height(14.dp).width(120.dp),
            shape = RoundedCornerShape(4.dp),
        )
    }
}

@Composable
fun PopularListSkeleton(rows: Int = 5, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp)) {
        repeat(rows) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShimmerPlaceholder(Modifier.size(48.dp), RoundedCornerShape(8.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    ShimmerPlaceholder(
                        Modifier.height(14.dp).fillMaxWidth(0.7f),
                        RoundedCornerShape(4.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    ShimmerPlaceholder(
                        Modifier.height(12.dp).fillMaxWidth(0.4f),
                        RoundedCornerShape(4.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun AlbumsRowSkeleton(count: Int = 6, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(count) {
            Column {
                ShimmerPlaceholder(Modifier.size(140.dp), RoundedCornerShape(8.dp))
                Spacer(Modifier.height(6.dp))
                ShimmerPlaceholder(Modifier.height(12.dp).width(120.dp))
            }
        }
    }
}
