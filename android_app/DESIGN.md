# DESIGN.md

> Style direction for ESP32 Smart Notification Android App

## Identity & Mood
- **Product**: ESP32 Smart Notification Companion
- **Personality**: Clean, calm, airy, and functional Scandinavian-inspired utility
- **Mood**: Serene, lightweight, distraction-free
- **Dials**: ENERGY 1 / RHYTHM 2 / MOTION 1

## Palette (Putih & Hijau Pastel)
- **Base Surface**: `#FFFFFF` (Pure crisp white cards and navigation bar)
- **Page Background**: `#F4F9F5` (Airy pastel mint-tinted off-white)
- **Primary Action**: `#327850` (Rich pastel forest green, 5.5:1 contrast against white)
- **Container / Active Pill**: `#D8F5DD` (Soft airy pastel mint container)
- **On Container**: `#184729` (Deep forest green text, 9.1:1 contrast)
- **Card Stroke**: `#D8EADF` (Delicate soft sage outline)
- **Card Stroke Subtle**: `#EBF4EE` (Inner hairline dividers)

### Typography Hierarchy (WCAG AA Compliant)
- **Primary Text**: `#193324` (Deep forest black, >13:1 contrast ratio)
- **Secondary Text**: `#3B6349` (Medium eucalyptus sage, 6.1:1 contrast ratio)
- **Muted Text**: `#527760` (Muted sage auxiliary text, 4.8:1 contrast ratio)

### Status Accents (Functional Indicators)
- **Connected**: `#247844` (Pastel lush green, 5.5:1 contrast)
- **Connecting**: `#9E6700` (Warm amber bronze, 4.7:1 contrast against white)
- **Disconnected**: `#C44F43` (Soft terracotta coral, 4.7:1 contrast)

## Layout & Components
- **Surfaces**: Crisp white flat cards with 20dp rounded corners and subtle 1dp sage borders (`card_stroke`).
- **Elevation**: Minimal elevation (0dp to 2dp) without exaggerated floating blur shadows.
- **Navigation**: Proportional WhatsApp-style Bottom Navigation Bar with pure white background and soft pastel mint pill indicator.
- **Touch Targets**: Minimum 44dp height on all interactive controls.
