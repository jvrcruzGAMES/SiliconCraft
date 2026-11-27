use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CompileError {
    pub message: String,
    pub file: Option<String>,
    pub line: usize,
    pub column: usize,
}

impl CompileError {
    pub fn new(message: impl Into<String>, file: Option<&str>, line: usize, column: usize) -> Self {
        Self {
            message: message.into(),
            file: file.map(|s| s.to_string()).filter(|s| !s.is_empty()),
            line,
            column,
        }
    }

    pub fn simple(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            file: None,
            line: 0,
            column: 0,
        }
    }
}

impl fmt::Display for CompileError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if self.line == 0 && self.column == 0 {
            return write!(f, "{}", self.message);
        }
        match &self.file {
            Some(file) => write!(f, "{}:{}:{}: {}", file, self.line, self.column, self.message),
            None => write!(f, "Line {}:{}: {}", self.line, self.column, self.message),
        }
    }
}

impl std::error::Error for CompileError {}
