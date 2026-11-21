package com.elect3.mobdev.personalfinance.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import com.elect3.mobdev.personalfinance.R
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

/** Keeps brand and data status together when they fit, otherwise stacks them. */
@Composable
fun OwliTopBar(modifier: Modifier = Modifier, dataLabel: String = stringResource(R.string.dashboard_demo_data)) {
    Layout(
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp)
            .semantics { isTraversalGroup = true },
        content = {
            OwliBrand(
                modifier = Modifier.semantics {
                    isTraversalGroup = true
                    traversalIndex = 0f
                },
            )
            OwliMockDataChip(
                text = dataLabel,
                modifier = Modifier.semantics {
                    isTraversalGroup = true
                    traversalIndex = 1f
                },
            )
        },
    ) { measurables, constraints ->
        val horizontalGap = 12.dp.roundToPx()
        val verticalGap = 8.dp.roundToPx()
        // Intrinsics include the children's actual text styles, font scale and padding.
        val brandWidth = measurables[0].maxIntrinsicWidth(Constraints.Infinity)
        val chipWidth = measurables[1].maxIntrinsicWidth(Constraints.Infinity)
        val horizontal = !constraints.hasBoundedWidth ||
            brandWidth.toLong() + horizontalGap + chipWidth <= constraints.maxWidth

        // Each child is measured once; stacked children can wrap to the available width.
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val brand = measurables[0].measure(childConstraints)
        val chip = measurables[1].measure(childConstraints)
        val contentWidth = if (horizontal) {
            brand.width + horizontalGap + chip.width
        } else {
            maxOf(brand.width, chip.width)
        }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else {
            constraints.constrainWidth(contentWidth)
        }
        val contentHeight = if (horizontal) maxOf(brand.height, chip.height) else {
            brand.height + verticalGap + chip.height
        }
        val height = constraints.constrainHeight(contentHeight)

        layout(width, height) {
            if (horizontal) {
                brand.placeRelative(0, (height - brand.height) / 2)
                chip.placeRelative(width - chip.width, (height - chip.height) / 2)
            } else {
                brand.placeRelative(0, 0)
                chip.placeRelative(0, brand.height + verticalGap)
            }
        }
    }
}
