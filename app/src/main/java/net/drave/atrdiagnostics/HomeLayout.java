package net.drave.atrdiagnostics;

/** Where things sit on the home screen right now, written by HomeActivity on every layout and read by the parts drawn over the map. */
final class HomeLayout {
    /** Landscape puts the sheet, search and turn card in a left column {@code panelW} wide and keeps the map on the right. */
    boolean landscape; int panelW;
    /** System bars and keyboard. */
    int top, bottom, ime;
    /** How much of the bottom sheet is on screen. */
    float sheet;
}
