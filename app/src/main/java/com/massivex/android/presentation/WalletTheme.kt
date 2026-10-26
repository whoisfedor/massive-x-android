package com.massivex.android.presentation

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable fun WalletTheme(preferences:Presentation,content: @Composable () -> Unit){
    val dark=PresentationPolicy.dark(preferences.appearance,isSystemInDarkTheme())
    val crimson=preferences.accent==Accent.CRIMSON
    val primary=when(preferences.accent){Accent.CRIMSON->if(dark)Color(0xFFF57894)else Color(0xFFA61A3D);Accent.MIDNIGHT->if(dark)Color(0xFF6EA8FF)else Color(0xFF1F52CC);Accent.ORANGE->if(dark)Color(0xFFFFA347)else Color(0xFF914005)}
    val background=if(dark){if(crimson)Color(0xFF0E0709)else Color(0xFF08090A)}else{if(crimson)Color(0xFFFBF2F5)else Color(0xFFF2F5FA)}
    val surface=if(dark){if(crimson)Color(0xFF1C0E13)else Color(0xFF121318)}else Color(0xFFFFFFFF)
    val colors=if(dark)darkColorScheme(primary=primary,onPrimary=Color(0xFF130B0F),primaryContainer=primary.copy(alpha=.2f),onPrimaryContainer=primary,background=background,surface=surface,surfaceContainer=surface,surfaceVariant=surface,onSurfaceVariant=Color(0xFF9A9CA4),onSurface=Color(0xFFF5F1F3),onBackground=Color(0xFFF5F1F3))
        else lightColorScheme(primary=primary,onPrimary=Color.White,primaryContainer=primary.copy(alpha=.12f),onPrimaryContainer=primary,background=background,surface=surface,surfaceContainer=surface,surfaceVariant=Color(0xFFF0EBEF),onSurface=Color(0xFF211A20),onBackground=Color(0xFF211A20))
    val context=androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.SideEffect {
        (context as? android.app.Activity)?.let{activity->
            val controller=androidx.core.view.WindowCompat.getInsetsController(activity.window,activity.window.decorView)
            controller.isAppearanceLightStatusBars=!dark;controller.isAppearanceLightNavigationBars=!dark
        }
    }
    MaterialTheme(colorScheme=colors,shapes=Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(20.dp),large=RoundedCornerShape(28.dp)),content=content)
}
