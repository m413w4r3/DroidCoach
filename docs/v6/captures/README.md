# Captures HOME du lot E

- `before-v5-home-light.png` est la capture V5 préexistante du HOME, reprise comme référence visuelle avant le lot E. Elle contient une directive et un favori synthétiques; aucun compte ni contenu personnel n'y apparaît.
- `after-home-dark.png` et `after-home-amoled.png` viennent du test Compose instrumenté `LotEUiInstrumentationTest.capturesSyntheticHomeInDarkAndAmoledThemes`, exécuté le 19 octobre 2026 sur l'AVD Android 17 `Pixel_9_Pro_XL_2` (`emulator-5554`). Il utilise un état synthétique et les préférences du paquet de test isolé.
- La V5 n'avait pas de choix AMOLED : cette référence antérieure est en clair système et ne constitue pas une capture AMOLED pré-lot. Les deux rendus après lot montrent le thème sombre et le noir AMOLED réellement implémentés.

Aucun AVD n'a été créé, effacé ou recréé. Les tests instrumentés ont réinitialisé uniquement les préférences privées du paquet `dev.atlas.coach.test`, jamais celles du paquet d'application `dev.atlas.coach`.
