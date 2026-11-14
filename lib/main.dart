import 'package:material_ui/material_ui.dart';

/// Memory Box — Phase 0 scaffold.
///
/// This is a minimal app shell only. It compiles and runs, and it states
/// honestly what is and is not built yet. No ingest, search, or Groq code
/// exists in this phase; those arrive in Phase 2+ per the PRD build order.
/// See README.md and PHASE1_REPORT.md.
void main() {
  runApp(const MemoryBoxApp());
}

class MemoryBoxApp extends StatelessWidget {
  const MemoryBoxApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Memory Box',
      theme: ThemeData(colorSchemeSeed: Colors.teal, useMaterial3: true),
      home: const Scaffold(
        body: Center(
          child: Padding(
            padding: EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Memory Box',
                  style: TextStyle(fontSize: 32, fontWeight: FontWeight.bold),
                ),
                SizedBox(height: 8),
                Text('Send any video. Save only its words.'),
                SizedBox(height: 24),
                Text('Phase 0 — project scaffold: done.'),
                Text('Phase 1 — proof gates (yt-dlp, sqlite-vec): see PHASE1_REPORT.md.'),
                Text('Phase 2+ — ingest, search, UI: not built yet.'),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
