import 'package:flutter_test/flutter_test.dart';
import 'package:arcolorchange/main.dart';

void main() {
  testWidgets('App smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(const ARColorChangeApp());
    expect(find.text('Camera Permission Required'), findsOneWidget);
  });
}
