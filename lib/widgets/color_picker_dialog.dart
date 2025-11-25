import 'package:flutter/material.dart';
import 'package:flutter_colorpicker/flutter_colorpicker.dart';

class ColorPickerDialog extends StatefulWidget {
  final Color initialColor;
  final Function(Color) onColorSelected;

  const ColorPickerDialog({
    super.key,
    required this.initialColor,
    required this.onColorSelected,
  });

  @override
  State<ColorPickerDialog> createState() => _ColorPickerDialogState();
}

class _ColorPickerDialogState extends State<ColorPickerDialog> {
  late Color _selectedColor;

  @override
  void initState() {
    super.initState();
    _selectedColor = widget.initialColor;
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Pick Wall Color'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ColorPicker(
              pickerColor: _selectedColor,
              onColorChanged: (color) {
                setState(() {
                  _selectedColor = color;
                });
              },
              pickerAreaHeightPercent: 0.7,
              enableAlpha: false,
              displayThumbColor: true,
              paletteType: PaletteType.hsvWithHue,
            ),
            const SizedBox(height: 16),
            _buildPresetColors(),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        ElevatedButton(
          onPressed: () {
            widget.onColorSelected(_selectedColor);
            Navigator.of(context).pop();
          },
          child: const Text('Apply'),
        ),
      ],
    );
  }

  Widget _buildPresetColors() {
    final presetColors = [
      const Color(0xFFF5F5DC), // Beige
      const Color(0xFFFFFFFF), // White
      const Color(0xFFF5F5F5), // Off-white
      const Color(0xFFD3D3D3), // Light gray
      const Color(0xFF87CEEB), // Sky blue
      const Color(0xFFE6E6FA), // Lavender
      const Color(0xFFFFE4C4), // Bisque
      const Color(0xFFFFF8DC), // Cornsilk
      const Color(0xFFDCDCDC), // Gainsboro
      const Color(0xFFF0E68C), // Khaki
      const Color(0xFFB0E0E6), // Powder blue
      const Color(0xFFFFDAB9), // Peach puff
    ];

    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: presetColors.map((color) {
        return GestureDetector(
          onTap: () {
            setState(() {
              _selectedColor = color;
            });
          },
          child: Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              color: color,
              border: Border.all(
                color: _selectedColor == color ? Colors.blue : Colors.grey,
                width: _selectedColor == color ? 3 : 1,
              ),
              borderRadius: BorderRadius.circular(8),
            ),
          ),
        );
      }).toList(),
    );
  }
}

Future<Color?> showColorPickerDialog(BuildContext context, Color initialColor) async {
  Color? selectedColor;
  await showDialog(
    context: context,
    builder: (context) => ColorPickerDialog(
      initialColor: initialColor,
      onColorSelected: (color) {
        selectedColor = color;
      },
    ),
  );
  return selectedColor;
}
