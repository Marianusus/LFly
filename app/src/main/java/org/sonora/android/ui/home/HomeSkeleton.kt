package org.sonora.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.sonora.android.ui.components.shimmer

@Composable
internal fun HomeHeroSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HomeDimens.Gutter, vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(HomeDimens.HeroArt)
                .shimmer(RoundedCornerShape(HomeDimens.CardRadius))
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .height(11.dp)
                    .shimmer(RoundedCornerShape(6.dp))
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(21.dp)
                    .shimmer(RoundedCornerShape(7.dp))
            )
            Spacer(modifier = Modifier.height(7.dp))
            Box(
                modifier = Modifier
                    .width(128.dp)
                    .height(14.dp)
                    .shimmer(RoundedCornerShape(6.dp))
            )
        }
    }
}

@Composable
internal fun HomeShelfSkeleton(
    columns: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HomeDimens.Gutter),
        verticalArrangement = Arrangement.spacedBy(HomeDimens.GridGap)
    ) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(HomeDimens.GridGap)) {
                repeat(columns) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(80.dp)
                            .shimmer(RoundedCornerShape(HomeDimens.CardRadius))
                    )
                }
            }
        }
    }
}

@Composable
internal fun HomeRailSkeleton(modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = HomeDimens.Gutter),
        horizontalArrangement = Arrangement.spacedBy(HomeDimens.GridGap)
    ) {
        items(3) {
            Column(modifier = Modifier.width(HomeDimens.RailCardWidth)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HomeDimens.RailCardWidth)
                        .shimmer(RoundedCornerShape(HomeDimens.CardRadius))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(15.dp)
                        .shimmer(RoundedCornerShape(6.dp))
                )
                Spacer(modifier = Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .height(12.dp)
                        .shimmer(RoundedCornerShape(6.dp))
                )
            }
        }
    }
}

@Composable
internal fun HomeChartsSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HomeDimens.Gutter),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        repeat(4) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .shimmer(RoundedCornerShape(16.dp)),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .width(22.dp)
                        .height(14.dp)
                        .shimmer(RoundedCornerShape(6.dp))
                )
                Box(
                    modifier = Modifier
                        .size(HomeDimens.ChartArt)
                        .shimmer(RoundedCornerShape(12.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(14.dp)
                            .shimmer(RoundedCornerShape(6.dp))
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.45f)
                            .height(11.dp)
                            .shimmer(RoundedCornerShape(6.dp))
                    )
                }
            }
        }
    }
}

@Composable
internal fun HomeEuropeSkeleton(modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = HomeDimens.Gutter),
        horizontalArrangement = Arrangement.spacedBy(HomeDimens.GridGap)
    ) {
        items(3) {
            Column(modifier = Modifier.width(HomeDimens.RailCardWidth)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HomeDimens.RailCardWidth)
                        .shimmer(RoundedCornerShape(HomeDimens.CardRadius))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(15.dp)
                        .shimmer(RoundedCornerShape(6.dp))
                )
                Spacer(modifier = Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(12.dp)
                        .shimmer(RoundedCornerShape(6.dp))
                )
            }
        }
    }
}
