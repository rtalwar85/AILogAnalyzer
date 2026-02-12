import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import LogAnalyzer from "./LogAnalyzer";

createRoot(document.getElementById("root")).render(
  <StrictMode>
    <LogAnalyzer />
  </StrictMode>
);
