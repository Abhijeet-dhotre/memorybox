// Phase 0 smoke test: the scaffold app builds and shows its status text.
// No ingest/search behaviour exists yet (Phase 2+), so there is nothing
// else to assert here.

import 'package:flutter_test/flutter_test.dart';

import 'package:memorybox/main.dart';

void main() {
  testWidgets('Memory Box scaffold shows Phase 0 status', (tester) async {
    await tester.pumpWidget(const MemoryBoxApp());

    expect(find.text('Memory Box'), findsOneWidget);
    expect(find.text('Phase 0 — project scaffold: done.'), findsOneWidget);
    expect(
      find.text('Phase 2+ — ingest, search, UI: not built yet.'),
      findsOneWidget,
    );
  });
}
